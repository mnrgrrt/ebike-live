package nl.meereboer.ebikelive.core

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Connection to a Bosch smart system eBike via the Live Data Interface.
 *
 * The LDI design has the eBike as the BLE central: it scans for accessories
 * that advertise a Service Solicitation for the LDI service and connects to
 * them. We support that path (advertise + wait). But some bikes, especially
 * after a firmware update, stop initiating that connection. So we ALSO do it
 * the other way around: the watch scans for the bike and connects to it as a
 * GATT client directly. Whichever path connects first wins.
 *
 * On every start we also log all nearby BLE devices, so the bike can be
 * recognised from the log.
 */
@SuppressLint("MissingPermission")
class LdiConnection(private val context: Context) {

    enum class Status { STOPPED, WAITING_FOR_BIKE, CONNECTING, LIVE, ERROR }

    private val _status = MutableStateFlow(Status.STOPPED)
    val status: StateFlow<Status> = _status
    private val _data = MutableStateFlow(LiveData())
    val data: StateFlow<LiveData> = _data
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("ebike", Context.MODE_PRIVATE)

    private var gattServer: BluetoothGattServer? = null
    private var gatt: BluetoothGatt? = null
    private var bikeDevice: BluetoothDevice? = null
    private var advertising = false
    private var scanning = false
    private var running = false

    // ---------------------------------------------------------------- public

    fun start() {
        if (running) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            fail("Bluetooth staat uit")
            return
        }
        running = true
        androidx.core.content.ContextCompat.registerReceiver(
            context, bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        _status.value = Status.WAITING_FOR_BIKE
        _message.value = "Fiets zoeken..."
        // Path 1: advertise so the bike can connect to us (classic LDI).
        if (a.bluetoothLeAdvertiser != null) {
            gattServer = manager?.openGattServer(context, serverCallback)
            startAdvertising()
        } else {
            log("no advertiser, central-only mode")
        }
        // Path 2: we go looking for the bike ourselves.
        startScan()
        // Path 3: keep a standing connect to the last known bike address.
        tryDirectConnect(storedBikeAddress())
    }

    fun stop() {
        if (!running) return
        running = false
        stopAdvertising()
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        gattServer?.close()
        gattServer = null
        bikeDevice = null
        runCatching { context.unregisterReceiver(bondReceiver) }
        _status.value = Status.STOPPED
        _message.value = ""
    }

    // ----------------------------------------------------------- bike address

    private fun storedBikeAddress(): String? =
        prefs.getString("bike_addr", null) ?: KNOWN_BIKE_ADDR

    private fun rememberBike(addr: String?) {
        if (addr.isNullOrBlank()) return
        if (prefs.getString("bike_addr", null) != addr) {
            prefs.edit().putString("bike_addr", addr).apply()
            log("remembered bike addr=$addr")
        }
    }

    // ----------------------------------------------------------- advertising

    private fun startAdvertising() {
        if (!running || advertising) return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val adv = AdvertiseData.Builder()
            .addServiceSolicitationUuid(ParcelUuid(LDI_SERVICE))
            .setIncludeDeviceName(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        runCatching { advertiser.startAdvertising(settings, adv, scanResponse, advertiseCallback) }
    }

    private fun stopAdvertising() {
        if (!advertising) return
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            log("advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) advertising = true
            else log("advertising failed code=$errorCode")
        }
    }

    // --------------------------------------------------------------- scanning

    private fun startScan() {
        if (!running || scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: run {
            log("no scanner available")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // No filter: we want to see and log everything nearby.
        runCatching { scanner.startScan(null, settings, scanCallback) }
            .onSuccess {
                scanning = true
                log("scan started")
            }
            .onFailure { log("scan start failed: ${it.message}") }
        // Active scanning is costly. Run a 30s burst, then stop; the standing
        // direct connect keeps trying after that.
        main.postDelayed({ stopScan() }, 30_000)
    }

    private fun stopScan() {
        if (!scanning) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        log("scan stopped")
    }

    private val seen = HashSet<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val rec = result.scanRecord
            val addr = dev.address
            // Log each distinct device once per scan burst.
            if (seen.add(addr)) {
                val name = rec?.deviceName ?: dev.name ?: "?"
                val services = rec?.serviceUuids?.joinToString(",") { it.uuid.toString() } ?: "-"
                val solicit = rec?.serviceSolicitationUuids?.joinToString(",") { it.uuid.toString() } ?: "-"
                log("scan dev=$addr name=$name rssi=${result.rssi} conn=${result.isConnectable} services=$services solicit=$solicit")
            }
            if (gatt != null) return
            val advertisesLdi =
                rec?.serviceUuids?.any { it.uuid == LDI_SERVICE } == true ||
                    rec?.serviceSolicitationUuids?.any { it.uuid == LDI_SERVICE } == true
            if (addr == storedBikeAddress() || advertisesLdi) {
                log("scan match $addr, connecting")
                connectClient(dev, autoConnect = false)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("scan failed code=$errorCode")
        }
    }

    private fun tryDirectConnect(addr: String?) {
        if (addr.isNullOrBlank() || gatt != null) return
        val dev = runCatching { adapter?.getRemoteDevice(addr) }.getOrNull() ?: return
        log("direct connect arm addr=$addr")
        connectClient(dev, autoConnect = true)
    }

    // ------------------------------------------------------------ GATT server

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                log("server link ${device.address} state=$newState status=$status")
                if (newState == BluetoothProfile.STATE_CONNECTED) connectClient(device, autoConnect = false)
                else if (device.address == bikeDevice?.address && gatt == null) resumeSearch()
            }
        }
    }

    // ------------------------------------------------------------ GATT client

    private var liveChar: BluetoothGattCharacteristic? = null
    private var subscribeRetries = 0

    private fun connectClient(device: BluetoothDevice, autoConnect: Boolean) {
        if (!running || gatt != null) return
        bikeDevice = device
        _status.value = Status.CONNECTING
        _message.value = "Fiets gevonden, verbinden..."
        log("open client ${device.address} auto=$autoConnect")
        gatt = device.connectGatt(context, autoConnect, clientCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                log("client state=$newState status=$status")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    rememberBike(g.device?.address)
                    stopAdvertising()
                    stopScan()
                    // LDI quirk: the bike does not start an MTU exchange itself.
                    if (!g.requestMtu(247)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    onBikeLost()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                log("mtu=$mtu status=$status")
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val ch = g.getService(LDI_SERVICE)?.getCharacteristic(LDI_LIVE_DATA)
                if (ch == null) {
                    fail("LDI-service niet gevonden op dit apparaat.")
                    return@post
                }
                liveChar = ch
                log("eb21 found props=0x%02x".format(ch.properties))
                subscribeRetries = 0
                subscribe(g, ch)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                log("cccd write status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    liveChar?.let { log("snapshot read issued=" + g.readCharacteristic(it)) }
                } else if (isAuthError(status)) {
                    ensureBond()
                } else {
                    fail("Aanmelden voor data mislukt (status $status)")
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handle(value)
        }

        @Deprecated("Pre API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) c.value?.let { handle(it) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            log("snapshot read result status=$status size=${value.size}")
            if (status == BluetoothGatt.GATT_SUCCESS) handle(value)
        }

        @Deprecated("Pre API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33 && status == BluetoothGatt.GATT_SUCCESS) c.value?.let { handle(it) }
        }
    }

    private fun onBikeLost() {
        gatt?.close()
        gatt = null
        liveChar = null
        bikeDevice = null
        if (!running) return
        resumeSearch()
    }

    private fun resumeSearch() {
        if (!running || gatt != null) return
        _status.value = Status.WAITING_FOR_BIKE
        _message.value = "Verbinding weg. Fiets zoeken..."
        seen.clear()
        startAdvertising()
        startScan()
        main.postDelayed({ tryDirectConnect(storedBikeAddress()) }, 1000)
    }

    private fun subscribe(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        log("subscribe: enabling notifications on eb21")
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD) ?: run {
            fail("Geen notificatie-descriptor op eb21")
            return
        }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    private fun handle(bytes: ByteArray) {
        log("RX ${bytes.size}B hex=" + bytes.joinToString("") { "%02x".format(it) })
        val update = LiveDataDecoder.decode(bytes)
        log("decoded=$update")
        main.post {
            _data.value = _data.value.merge(update)
            if (_status.value != Status.LIVE) {
                _status.value = Status.LIVE
                _message.value = "Live"
            }
        }
    }

    // ---------------------------------------------------------------- bonding

    private fun isAuthError(status: Int) =
        status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
            status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION || status == 137

    private fun ensureBond() {
        val d = bikeDevice ?: return
        _message.value = "Koppelen met fiets..."
        if (d.bondState == BluetoothDevice.BOND_NONE) d.createBond()
        else retrySubscribe()
    }

    private fun retrySubscribe() {
        val g = gatt ?: return
        val ch = liveChar ?: return
        if (subscribeRetries++ >= 3) {
            fail("Koppelen lukt niet. Verwijder de koppeling in Flow en op dit apparaat en probeer opnieuw.")
            return
        }
        main.postDelayed({ subscribe(g, ch) }, 1000)
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (dev?.address != bikeDevice?.address) return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            log("bond state=$state")
            if (state == BluetoothDevice.BOND_BONDED) retrySubscribe()
        }
    }

    // ------------------------------------------------------------------ misc

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        _status.value = Status.ERROR
        _message.value = msg
    }

    private fun log(msg: String) = Log.i(TAG, msg)

    companion object {
        private const val TAG = "EbikeLdi"
        // Bike's BLE address from an earlier successful session, used as the
        // default direct-connect target until the app learns the real one.
        private const val KNOWN_BIKE_ADDR = "00:04:63:A4:37:BB"
        val LDI_SERVICE: UUID = UUID.fromString("0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4")
        val LDI_LIVE_DATA: UUID = UUID.fromString("0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
