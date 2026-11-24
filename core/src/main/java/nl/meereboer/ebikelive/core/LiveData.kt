package nl.meereboer.ebikelive.core

/** Support level, with the colour Bosch uses on the remote. */
enum class AssistMode(val label: String, val color: Long) {
    OFF("Uit", 0xFFFFFFFF),
    ECO("Eco", 0xFF2ECC40),
    TOUR("Tour", 0xFF2F80ED),
    SPORT("Sport", 0xFF9B51E0),
    TURBO("Turbo", 0xFFEB3B3B),
    UNKNOWN("Modus onbekend", 0xFF5F5E5A),
}

/**
 * Live data from the Bosch smart system (Live Data Interface v1.0).
 * Notifications are sparse: only changed fields are sent. [merge] keeps the
 * last known value of every field.
 */
data class LiveData(
    val speedKmh: Double? = null,
    val cadence: Int? = null,
    val riderPower: Int? = null,
    val batterySoc: Int? = null,
    val odometerKm: Double? = null,
    val lightOn: Boolean? = null,
    val locked: Boolean? = null,
    val chargerConnected: Boolean? = null,
    val inMotion: Boolean? = null,
    /** LDI v1.0 has no field for this yet. Stays null until Bosch adds one. */
    val assistMode: AssistMode? = null,
    /** Varint fields this app does not know yet (field number to value). */
    val unknownFields: Map<Int, Long> = emptyMap(),
) {
    fun merge(update: LiveData) = LiveData(
        speedKmh = update.speedKmh ?: speedKmh,
        cadence = update.cadence ?: cadence,
        riderPower = update.riderPower ?: riderPower,
        batterySoc = update.batterySoc ?: batterySoc,
        odometerKm = update.odometerKm ?: odometerKm,
        lightOn = update.lightOn ?: lightOn,
        locked = update.locked ?: locked,
        chargerConnected = update.chargerConnected ?: chargerConnected,
        inMotion = update.inMotion ?: inMotion,
        assistMode = update.assistMode ?: assistMode,
        unknownFields = unknownFields + update.unknownFields,
    )
}

/** Decoder for the LDI LiveData protobuf. All fields are varints. */
object LiveDataDecoder {

    fun decode(data: ByteArray): LiveData {
        var out = LiveData()
        var pos = 0
        while (pos < data.size) {
            val (tag, p1) = readVarint(data, pos) ?: break
            pos = p1
            val field = (tag ushr 3).toInt()
            when ((tag and 7L).toInt()) {
                0 -> {
                    val (v, p2) = readVarint(data, pos) ?: break
                    pos = p2
                    out = apply(out, field, v)
                }
                1 -> pos += 8
                2 -> {
                    val (len, p2) = readVarint(data, pos) ?: break
                    pos = p2 + len.toInt()
                }
                5 -> pos += 4
                else -> break
            }
        }
        return out
    }

    private fun apply(d: LiveData, field: Int, v: Long): LiveData = when (field) {
        1 -> d.copy(speedKmh = v / 100.0)            // 1/100 km/h
        2 -> d.copy(cadence = v.toInt())             // rpm, signed int32
        5 -> d.copy(riderPower = v.toInt())          // W
        10 -> d.copy(batterySoc = v.toInt())         // %
        12 -> d.copy(odometerKm = v / 1000.0)        // metres
        17 -> d.copy(lightOn = v == 2L)              // 2 = on, 1 = off
        21 -> d.copy(locked = v != 0L)
        22 -> d.copy(chargerConnected = v != 0L)
        25 -> d.copy(inMotion = v == 0L)             // wire: bike_not_driving
        11, 9, 23, 24 -> d                            // known, not shown
        else -> d.copy(unknownFields = d.unknownFields + (field to v))
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            pos++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to pos
            shift += 7
            if (shift >= 64) return null
        }
        return null
    }
}
