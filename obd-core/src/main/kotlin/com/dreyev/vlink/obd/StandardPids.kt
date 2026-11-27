package com.dreyev.vlink.obd

/** SAE J1979 mode 01 PIDs used by the dashboard, with their decoders. */
object StandardPids {
    const val SPEED = 0x0D        // km/h = A
    const val RPM = 0x0C          // rpm = (256A+B)/4
    const val LOAD = 0x04         // % = A*100/255
    const val THROTTLE = 0x11     // % = A*100/255
    const val PEDAL_D = 0x49      // % = A*100/255 (accelerator pedal position D)
    const val AMBIENT = 0x46      // °C = A-40
    const val RUNTIME = 0x1F      // s = 256A+B
    const val ODOMETER = 0xA6     // km = (A<<24|B<<16|C<<8|D)/10

    fun speedKmh(b: IntArray) = b[0].toDouble()
    fun rpm(b: IntArray) = (b[0] * 256 + b[1]) / 4.0
    fun percent(b: IntArray) = b[0] * 100.0 / 255.0
    fun ambientC(b: IntArray) = b[0] - 40.0
    fun runtimeS(b: IntArray) = (b[0] * 256L + b[1])
    fun odometerKm(b: IntArray) =
        ((b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()) / 10.0

    /** Decode a "supported PIDs" bitmap reply (0100, 0120, …) into PID numbers. */
    fun supported(base: Int, b: IntArray): Set<Int> {
        val out = mutableSetOf<Int>()
        for (i in 0 until minOf(4, b.size)) for (bit in 0 until 8) {
            if (b[i] and (0x80 shr bit) != 0) out += base + i * 8 + bit + 1
        }
        return out
    }

    fun hex(pid: Int) = String.format("%02X", pid)
}

/** VIN helpers (mode 09 PID 02). */
object Vin {
    /** Pull the 17 VIN characters out of a mode-09 reply, skipping the item-count byte and padding. */
    fun decode(bytes: IntArray): String? {
        val chars = bytes.filter { it in 0x30..0x5A }.map { it.toChar() }.joinToString("")
        return if (chars.length >= 17) chars.takeLast(17) else null
    }

    private val years = "ABCDEFGHJKLMNPRSTVWXY123456789"

    /** Model year from the 10th character (2010–2039 cycle; letters before 2010 map to the 1980–2009 cycle). */
    fun modelYear(vin: String): Int? {
        if (vin.length != 17) return null
        val i = years.indexOf(vin[9])
        if (i < 0) return null
        val y = 1980 + i
        // 7th character numeric → 1980–2009 cycle, letter → 2010–2039 (North American convention).
        return if (vin[6].isLetter()) y + 30 else y
    }

    private val wmi = mapOf(
        "1HG" to "Honda", "JHM" to "Honda", "2HG" to "Honda", "5J6" to "Honda",
        "1FA" to "Ford", "1FT" to "Ford", "1FM" to "Ford", "3FA" to "Ford",
        "1G1" to "Chevrolet", "1GC" to "Chevrolet", "1GN" to "Chevrolet", "2G1" to "Chevrolet",
        "JTD" to "Toyota", "JTE" to "Toyota", "4T1" to "Toyota", "5TD" to "Toyota", "2T1" to "Toyota", "JTM" to "Toyota",
        "1N4" to "Nissan", "JN1" to "Nissan", "5N1" to "Nissan",
        "WBA" to "BMW", "WBS" to "BMW", "5UX" to "BMW",
        "WDD" to "Mercedes-Benz", "WDC" to "Mercedes-Benz", "4JG" to "Mercedes-Benz",
        "WVW" to "Volkswagen", "3VW" to "Volkswagen", "WAU" to "Audi",
        "KMH" to "Hyundai", "5NP" to "Hyundai", "KNA" to "Kia", "KND" to "Kia",
        "5YJ" to "Tesla", "7SA" to "Tesla", "JF1" to "Subaru", "4S4" to "Subaru",
        "JM1" to "Mazda", "1C4" to "Jeep/Chrysler", "1C6" to "Ram", "2C3" to "Chrysler",
        "YV1" to "Volvo", "SAL" to "Land Rover", "ZFA" to "Fiat",
    )

    fun make(vin: String): String? = if (vin.length >= 3) wmi[vin.substring(0, 3).uppercase()] else null

    fun describe(vin: String): String {
        val parts = listOfNotNull(modelYear(vin)?.toString(), make(vin))
        return if (parts.isEmpty()) "Make not recognized" else parts.joinToString(" ")
    }
}
