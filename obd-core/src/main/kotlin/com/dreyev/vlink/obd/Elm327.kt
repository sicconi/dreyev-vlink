package com.dreyev.vlink.obd

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Result of one OBD request. */
sealed interface ObdResult {
    /** Data bytes after the response prefix (e.g. for 010D → the single speed byte). */
    data class Data(val bytes: IntArray) : ObdResult
    data class NoData(val reason: String) : ObdResult
    data class Failure(val reason: String) : ObdResult
}

data class ElmInfo(val version: String, val protocol: String, val voltage: Double?)

/**
 * Talks to an ELM327-compatible chip (VLink uses one). Handles initialization,
 * the '>' prompt, headers, multi-line CAN replies and error strings.
 */
class Elm327(
    private val transport: ObdTransport,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private var header: String? = null
    private var isCan = true
    var protocol: String = "?"
        private set

    /** Send a raw command and return the text before the '>' prompt. */
    suspend fun command(cmd: String, timeoutMs: Long = 2_000): String = mutex.withLock {
        rawCommand(cmd, timeoutMs)
    }

    private suspend fun rawCommand(cmd: String, timeoutMs: Long): String {
        transport.drain()
        transport.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                while (true) {
                    val chunk = transport.read()
                    sb.append(String(chunk, Charsets.ISO_8859_1))
                    if (sb.indexOf(">") >= 0) break
                }
            }
        } catch (e: TimeoutCancellationException) {
            log("timeout on $cmd: '${sb.toString().trim()}'")
            if (sb.isEmpty()) throw ElmTimeoutException(cmd)
        }
        val text = sb.toString().substringBefore('>').replace("\u0000", "")
        log("$cmd → ${text.trim().replace('\r', '|')}")
        return text
    }

    /** Standard ELM327 setup. Works with VLink, vLinker and most clones. */
    suspend fun initialize(): ElmInfo = mutex.withLock {
        // ATZ resets the chip; some clones take ~1 s and print the banner twice.
        val banner = try { rawCommand("ATZ", 4_000) } catch (_: ElmTimeoutException) { "" }
        delay(300)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATCAF1", "ATAT1", "ATST64", "ATSP0")) {
            rawCommand(c, 2_000)
        }
        val version = rawCommand("ATI", 2_000).lines().map { it.trim() }
            .lastOrNull { it.startsWith("ELM", true) || it.contains("v", true) }
            ?: banner.lines().map { it.trim() }.lastOrNull { it.startsWith("ELM", true) } ?: "ELM327"
        // First PID request triggers the automatic protocol search (can take several seconds).
        rawCommand("0100", 12_000)
        val dpn = rawCommand("ATDPN", 2_000).trim().removePrefix("A")
        protocol = protocolName(dpn)
        isCan = dpn.firstOrNull()?.let { it in '6'..'9' || it in 'A'..'C' } ?: true
        val volts = parseVoltage(rawCommand("ATRV", 2_000))
        header = null
        ElmInfo(version, protocol, volts)
    }

    suspend fun voltage(): Double? = parseVoltage(command("ATRV"))

    /**
     * Send an OBD request such as "010D" or "221234".
     * [header] selects the target ECU for manufacturer PIDs (e.g. "7B0"); null = broadcast.
     * [prefix] overrides the expected response prefix (default: mode+0x40 followed by the PID bytes).
     */
    suspend fun query(
        request: String,
        header: String? = null,
        prefix: String? = null,
        timeoutMs: Long = 1_500,
    ): ObdResult = mutex.withLock {
        try {
            if (header != this.header) {
                val target = header ?: if (isCan) "7DF" else "686AF1"
                rawCommand("ATSH$target", 1_000)
                if (isCan) rawCommand(if (header == null) "ATCRA" else "ATCRA${responseHeaderFor(header)}", 1_000)
                this.header = header
            }
            val raw = rawCommand(request.replace(" ", ""), timeoutMs)
            parse(raw, prefix ?: defaultPrefix(request))
        } catch (e: ElmTimeoutException) {
            ObdResult.Failure("timeout")
        }
    }

    companion object {
        private val ERRORS = listOf(
            "NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS BUSY",
            "DATA ERROR", "STOPPED", "ERROR", "?", "FB ERROR", "BUFFER FULL", "LV RESET",
        )

        /** 11-bit CAN: ECU at 7E0 answers on 7E8, 7B0 → 7B8, etc. 29-bit left unfiltered. */
        fun responseHeaderFor(h: String): String =
            if (h.length == 3) (h.toInt(16) + 8).toString(16).uppercase() else ""

        fun defaultPrefix(request: String): String {
            val r = request.replace(" ", "").uppercase()
            val mode = r.substring(0, 2).toInt(16) + 0x40
            return String.format("%02X", mode) + r.substring(2)
        }

        fun parseVoltage(text: String): Double? =
            Regex("""(\d+(\.\d+)?)\s*V""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toDoubleOrNull()

        fun protocolName(dpn: String): String = when (dpn.trim().uppercase()) {
            "1" -> "SAE J1850 PWM"
            "2" -> "SAE J1850 VPW"
            "3" -> "ISO 9141-2"
            "4" -> "ISO 14230-4 KWP (5 baud)"
            "5" -> "ISO 14230-4 KWP (fast)"
            "6" -> "ISO 15765-4 CAN 11/500"
            "7" -> "ISO 15765-4 CAN 29/500"
            "8" -> "ISO 15765-4 CAN 11/250"
            "9" -> "ISO 15765-4 CAN 29/250"
            "A" -> "SAE J1939 CAN"
            else -> "Auto ($dpn)"
        }

        /**
         * Parse an ELM reply with headers off. Handles:
         *  - single frames:          "410D3C"
         *  - several ECUs answering: "410D3C\r410D3D" (first match wins)
         *  - ISO-TP multi-frame:     "014\r0:490201314847\r1:434D3832363333\r2:..."
         *  - legacy multi-line:      "490201000000 31\r49020231484743..." (mode 09 on K-line)
         */
        fun parse(raw: String, prefix: String): ObdResult {
            val lines = raw.split('\r', '\n').map { it.trim().replace(" ", "") }
                .filter { it.isNotEmpty() && !it.startsWith("SEARCHING", true) && !it.startsWith("BUSINIT", true) }
            if (lines.isEmpty()) return ObdResult.NoData("empty")
            lines.firstOrNull { l -> ERRORS.any { l.equals(it.replace(" ", ""), true) } }?.let {
                return if (it.equals("NODATA", true)) ObdResult.NoData("NO DATA") else ObdResult.Failure(it)
            }
            val pfx = prefix.uppercase()
            val isMulti = lines.any { Regex("^[0-9A-F]:").containsMatchIn(it) }
            val frames: List<String> = if (isMulti) {
                listOf(lines.filter { Regex("^[0-9A-F]:").containsMatchIn(it) }.joinToString("") { it.substring(2) })
            } else lines
            val hexOnly = frames.filter { it.matches(Regex("^[0-9A-Fa-f]+$")) && it.length % 2 == 0 }
            // Legacy multi-line mode 09: every line repeats the prefix plus a sequence byte.
            if (!isMulti && pfx.startsWith("49") && hexOnly.count { it.uppercase().startsWith(pfx) } > 1) {
                val data = hexOnly.filter { it.uppercase().startsWith(pfx) }
                    .flatMap { hexToInts(it.substring(pfx.length + 2)) }
                return ObdResult.Data(data.toIntArray())
            }
            for (f in hexOnly) {
                val idx = f.uppercase().indexOf(pfx)
                if (idx >= 0 && idx % 2 == 0) {
                    return ObdResult.Data(hexToInts(f.substring(idx + pfx.length)).toIntArray())
                }
            }
            return ObdResult.NoData("unexpected reply: ${lines.joinToString("|")}")
        }

        fun hexToInts(s: String): List<Int> = s.chunked(2).filter { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }
    }
}

class ElmTimeoutException(cmd: String) : Exception("No reply from adapter to $cmd")
