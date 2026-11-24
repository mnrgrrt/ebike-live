package nl.meereboer.ebikelive.core

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
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
 * Roles are reversed compared to most BLE gadgets: the eBike is the BLE
 * central. It scans for accessories that advertise a Service Solicitation
 * for the LDI service and connects to them. Once the link is up we act as
 * GATT client on that same link and subscribe to the live data
 * characteristic (eb21).
 *
 * First time: in the Bosch Flow app go to the bike, gear icon, Components,
 * "Add new device". The bike scans and finds this phone or watch. Confirm
 * on the bike display. After that the bike reconnects by itself whenever it
 * is switched on and this app is running.
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

    private var gattServer: BluetoothGattServer? = null
    private var gatt: BluetoothGatt? = null
    private var bikeDevice: BluetoothDevice? = null
    private var advertising = false
    private var running = false

    // ---------------------------------------------------------------- public

    fun start() {
        if (running) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            fail("Bluetooth staat uit")
            return
        }
        if (a.bluetoothLeAdvertiser == null) {
            fail("Dit apparaat kan geen Bluetooth-advertising (peripheral mode)")
            return
        }
        running = true
        androidx.core.content.ContextCompat.registerReceiver(
            context, bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        // An (empty) GATT server gives us callbacks when the bike connects.
        gattServer = manager?.openGattServer(context, serverCallback)
        startAdvertising()
    }

    fun stop() {
        if (!running) return
        running = false
        stopAdvertising()
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

    // ----------------------------------------------------------- advertising

    private fun startAdvertising() {
        if (!running || advertising) return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        // Flags (3) + 128-bit solicitation (18) = 21 of 31 bytes.
        val adv = AdvertiseData.Builder()
            .addServiceSolicitationUuid(ParcelUuid(LDI_SERVICE))
            .setIncludeDeviceName(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        advertiser.startAdvertising(settings, adv, scanResponse, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (!advertising) return
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            if (_status.value != Status.LIVE) {
                _status.value = Status.WAITING_FOR_BIKE
                _message.value = "Wacht op fiets. Zet de fiets aan."
            }
            log("advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                advertising = true
                return
            }
            fail("Advertising mislukt (code $errorCode)")
        }
    }

    // ------------------------------------------------------------ GATT server

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                log("server link ${device.address} state=$newState status=$status")
                if (newState == BluetoothProfile.STATE_CONNECTED) onBikeLinked(device)
                else if (device.address == bikeDevice?.address) onBikeLost()
            }
        }
    }

    private fun onBikeLinked(device: BluetoothDevice) {
        if (!running) return
        if (gatt != null && bikeDevice?.address == device.address) return
        bikeDevice = device
        stopAdvertising()
        _status.value = Status.CONNECTING
        _message.value = "Fiets verbonden, data ophalen..."
        // Opens a GATT client on the link the bike just made.
        gatt = device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun onBikeLost() {
        gatt?.close()
        gatt = null
        bikeDevice = null
        if (!running) return
        _status.value = Status.WAITING_FOR_BIKE
        _message.value = "Verbinding weg. Wacht op fiets."
        main.postDelayed({ startAdvertising() }, 500)
    }

    // ------------------------------------------------------------ GATT client

    private var liveChar: BluetoothGattCharacteristic? = null
    private var subscribeRetries = 0

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                log("client state=$newState status=$status")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
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
                    fail("LDI-service niet gevonden. Fiets-firmware bijwerken via Flow (v19 of hoger).")
                    return@post
                }
                liveChar = ch
                subscribeRetries = 0
                subscribe(g, ch)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                log("cccd write status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // A read returns a full snapshot, notifications only changes.
                    liveChar?.let { g.readCharacteristic(it) }
                } else if (isAuthError(status)) {
                    ensureBond()
                } else {
                    fail("Aanmelden voor data mislukt (status $status)")
                }
            }
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handle(value)
        }

        @Deprecated("Pre API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) c.value?.let { handle(it) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handle(value)
        }

        @Deprecated("Pre API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33 && status == BluetoothGatt.GATT_SUCCESS) c.value?.let { handle(it) }
        }
    }

    private fun subscribe(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
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
        val update = LiveDataDecoder.decode(bytes)
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
        val LDI_SERVICE: UUID = UUID.fromString("0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4")
        val LDI_LIVE_DATA: UUID = UUID.fromString("0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
