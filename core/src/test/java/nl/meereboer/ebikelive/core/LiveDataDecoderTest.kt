package nl.meereboer.ebikelive.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveDataDecoderTest {

    private fun varint(v: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var x = v
        do {
            var b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x != 0L) b = b or 0x80
            out.add(b.toByte())
        } while (x != 0L)
        return out.toByteArray()
    }

    private fun field(no: Int, v: Long) = varint((no.toLong() shl 3)) + varint(v)

    @Test
    fun decodesSpeedAndBattery() {
        val d = LiveDataDecoder.decode(field(1, 2534) + field(10, 87) + field(12, 1234567))
        assertEquals(25.34, d.speedKmh!!, 0.001)
        assertEquals(87, d.batterySoc)
        assertEquals(1234.567, d.odometerKm!!, 0.001)
    }

    @Test
    fun negativeCadenceAndUnknownFields() {
        // field 99 length-delimited (skipped), cadence -5 as int32 varint (10 bytes)
        val unknown = varint((99L shl 3) or 2) + varint(3) + byteArrayOf(1, 2, 3)
        val d = LiveDataDecoder.decode(unknown + field(2, -5L) + field(25, 0))
        assertEquals(-5, d.cadence)
        assertEquals(true, d.inMotion)
    }

    @Test
    fun mergeKeepsOldValues() {
        val a = LiveData(speedKmh = 20.0, batterySoc = 80)
        val b = a.merge(LiveData(speedKmh = 21.0))
        assertEquals(80, b.batterySoc)
        assertEquals(21.0, b.speedKmh!!, 0.0)
    }
}
