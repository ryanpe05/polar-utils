package com.polarutils.rrlogger

/**
 * Parses a BLE Heart Rate Measurement (0x2A37) notification.
 *
 * Layout per the BLE Heart Rate Service spec:
 *   byte 0      : flags
 *                   bit 0  -> HR value format (0 = uint8, 1 = uint16)
 *                   bit 3  -> energy expended present
 *                   bit 4  -> RR intervals present
 *   bytes 1..N  : HR value (uint8 or uint16 LE)
 *   (optional)  : energy expended (uint16 LE) if flag bit 3 set
 *   (optional)  : RR intervals, each uint16 LE in units of 1/1024 s
 *
 * Mirrors the parsing in ../../ble_rr/record.py.
 */
object HrParser {

    data class Measurement(val hr: Int, val rrMs: List<Double>)

    fun parse(data: ByteArray): Measurement {
        if (data.isEmpty()) return Measurement(0, emptyList())

        val flags = data[0].toInt() and 0xFF
        val hr16bit = (flags and 0x01) != 0
        val energyPresent = (flags shr 3 and 0x01) != 0
        val rrPresent = (flags shr 4 and 0x01) != 0

        var offset = 1
        val hr: Int
        if (hr16bit) {
            hr = u16le(data, offset)
            offset += 2
        } else {
            hr = data[offset].toInt() and 0xFF
            offset += 1
        }

        if (energyPresent) offset += 2

        val rrMs = ArrayList<Double>()
        if (rrPresent) {
            while (offset + 1 < data.size) {
                val rr1024 = u16le(data, offset)
                offset += 2
                // 1/1024 s units -> ms, rounded to 2 decimals like record.py
                rrMs.add(Math.round(rr1024 * 1000.0 / 1024.0 * 100.0) / 100.0)
            }
        }
        return Measurement(hr, rrMs)
    }

    private fun u16le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
