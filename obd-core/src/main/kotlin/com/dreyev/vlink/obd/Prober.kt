package com.dreyev.vlink.obd

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Signal finder: tools for discovering manufacturer data a car exposes over the OBD port.
 *
 *  1. [checkProfile] sends every request in the active profile and records the raw reply, so
 *     a signal that shows N/A can be traced to "ECU did not answer", "negative response",
 *     or "answered but the formula reads past the end".
 *  2. [scan] pings a list of ECU addresses and, for each one that answers, tries a list of
 *     UDS ReadDataByIdentifier (0x22) DIDs, keeping the ones with a positive reply.
 *  3. [watch] re-reads the DIDs found by the scan in a loop, tagging every row with what the
 *     driver is doing ("left signal on", "driver belt buckled"…). Comparing rows by tag
 *     shows which byte and bit follows each action.
 *
 * Every row goes to [sink] (the app writes them to a CSV the user can share).
 */
class Prober(
    private val elm: Elm327,
    private val sink: (ProbeRow) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One request/reply. [hex] is the data after the 62xxxx prefix when the reply was positive. */
    data class ProbeRow(
        val timeMs: Long,
        val phase: String,
        val header: String,
        val request: String,
        val status: String,
        val hex: String,
        val raw: String,
        val latencyMs: Long,
        val marker: String = "",
        val note: String = "",
    ) {
        val ok get() = status == "OK"
        val bytes: IntArray get() = Elm327.hexToInts(hex).toIntArray()

        fun csv(): String = listOf(
            timeMs.toString(), phase, header, request, status, hex, raw, latencyMs.toString(), marker, note,
        ).joinToString(",") { v -> if (v.any { it == ',' || it == '"' || it == '|' }) "\"" + v.replace("\"", "\"\"") + "\"" else v }

        companion object {
            const val CSV_HEADER = "time_ms,phase,header,request,status,data_hex,raw,latency_ms,marker,note"
        }
    }

    data class Target(val header: String, val request: String) {
        val key get() = "$header $request"
    }

    /** Send one request and classify the reply. */
    suspend fun probe(phase: String, header: String, request: String, marker: String = "", timeoutMs: Long = 1_500): ProbeRow {
        val t0 = clock()
        val r = elm.queryRaw(request, header, timeoutMs = timeoutMs)
        val ms = clock() - t0
        val (status, hex) = classify(request, r)
        return ProbeRow(t0, phase, header, request, status, hex, r.raw, ms, marker).also(sink)
    }

    /** Check every request of [profile]; note which signals decode from each reply. */
    suspend fun checkProfile(profile: ManufacturerProfile, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<ProbeRow> {
        val groups = profile.pids.groupBy { Target(it.header ?: "7DF", it.request) }
        val out = ArrayList<ProbeRow>()
        groups.entries.forEachIndexed { i, (target, pids) ->
            if (!currentCoroutineContext().isActive) return out
            onProgress(i, groups.size)
            var row = probe("profile", target.header, target.request)
            // One retry with a longer wait: some gateways are slow to wake a sleeping ECU.
            if (!row.ok) row = probe("profile-retry", target.header, target.request, timeoutMs = 4_000)
            val decoded = if (row.ok) pids.joinToString("; ") { p ->
                val need = p.formula.bytesNeeded
                if (row.bytes.size < need) "${p.signal.id}=short reply (needs $need bytes, got ${row.bytes.size})"
                else "${p.signal.id}=" + (runCatching { UnitPrefs.fmt(p.toMetric(p.formula.eval(row.bytes)), 2) }.getOrElse { "error: ${it.message}" })
            } else pids.joinToString(", ") { it.signal.id }
            val noted = row.copy(note = decoded)
            sink(noted.copy(phase = "profile-result"))
            out += noted
        }
        onProgress(groups.size, groups.size)
        return out
    }

    /**
     * Ping each ECU in [ecus], then read each DID in [dids] from the ones that answer.
     * Returns the positive replies (the candidates for [watch]).
     */
    suspend fun scan(
        ecus: List<String>,
        dids: (String) -> List<String>,
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
    ): List<ProbeRow> {
        val found = ArrayList<ProbeRow>()
        ecus.forEachIndexed { i, ecu ->
            if (!currentCoroutineContext().isActive) return found
            onProgress("Looking for ECU $ecu", i, ecus.size)
            if (!isPresent(ecu)) return@forEachIndexed
            val list = dids(ecu)
            list.forEachIndexed { j, did ->
                if (!currentCoroutineContext().isActive) return found
                if (j % 4 == 0) onProgress("ECU $ecu: DID $did (${j + 1}/${list.size})", i, ecus.size)
                val row = probe("scan", ecu, "22$did")
                if (row.ok) found += row
            }
        }
        onProgress("Done", ecus.size, ecus.size)
        return found
    }

    /** True if [ecu] answers anything at all (tester present, or a positive/negative 0x22 reply). */
    suspend fun isPresent(ecu: String): Boolean {
        val tp = probe("ping", ecu, "3E00")
        if (tp.status == "OK" || tp.status.startsWith("NEG")) return true
        // Some ECUs ignore tester present; a negative reply to an unlikely DID also proves they exist.
        val any = probe("ping", ecu, "22F190")
        return any.status == "OK" || any.status.startsWith("NEG")
    }

    /**
     * Read [targets] in a loop until cancelled. [marker] is read before every request, so the
     * user can change it (e.g. "left signal on") while this runs.
     */
    suspend fun watch(targets: List<Target>, marker: () -> String, onRow: (ProbeRow) -> Unit = {}) {
        while (currentCoroutineContext().isActive) {
            for (t in targets) {
                if (!currentCoroutineContext().isActive) return
                onRow(probe("watch", t.header, t.request, marker()))
            }
        }
    }

    companion object {
        /** ECU addresses on Hyundai/Kia E-GMP cars, plus common neighbors worth trying. */
        val EGMP_ECUS = listOf(
            "770",  // IGPM / body (lights, brake lamp)
            "7A0",  // BCM / TPMS / smart key
            "7C6",  // instrument cluster (odometer)
            "730",  // chassis gateway: wheel speeds, steering, accel, yaw
            "7D1",  // ESC / IEB brakes
            "7D4",  // MDPS steering
            "7D2",  // airbag (ACU): seat belt buckles
            "7E2",  // VCU (gear, pedal)
            "7E3",  // MCU (motor)
            "7E4",  // BMS
            "7E5",  // OBC
            "744",  // VCMS / charge control
            "7B3",  // HVAC
            "7A2", "7A5", "7A6", "7B1", "7C4", "7C7", "7D0", "7D3", "7D5", "7D6", "794", "796",
        )

        private fun range(prefix: String, from: Int, to: Int) = (from..to).map { prefix + String.format("%02X", it) }

        /** DIDs tried on each ECU: the families Hyundai/Kia use for live data. */
        fun defaultDids(ecu: String): List<String> {
            val common = range("01", 0x00, 0x0F) + range("B0", 0x01, 0x10) + range("BC", 0x01, 0x10) +
                range("C0", 0x01, 0x0F) + range("E0", 0x01, 0x11) + range("F0", 0x10, 0x1F)
            val extra = when (ecu) {
                "7D2" -> range("B0", 0x11, 0x20) + range("C1", 0x01, 0x08)   // airbag: belt buckles live here on older models
                "770", "7A0" -> range("BC", 0x11, 0x20) + range("B1", 0x01, 0x08)
                else -> emptyList()
            }
            return (common + extra).distinct()
        }

        /** ECUs worth watching for driver actions (body and chassis, not battery cell data). */
        val WATCH_ECUS = setOf("770", "7A0", "7C6", "730", "7D1", "7D4", "7D2", "7E2", "7A2", "7A5", "7A6", "7C4", "7C7", "7D0", "7D3")

        fun classify(request: String, r: RawReply): Pair<String, String> = when (val res = r.result) {
            is ObdResult.Data -> "OK" to res.bytes.joinToString("") { String.format("%02X", it) }
            is ObdResult.NoData -> {
                val compact = r.raw.replace("|", "").replace(" ", "").uppercase()
                val svc = request.take(2).uppercase()
                val neg = Regex("7F$svc([0-9A-F]{2})").find(compact)?.groupValues?.get(1)
                when {
                    neg != null -> "NEG $neg ${nrcName(neg)}".trim() to ""
                    svc == "3E" && compact.contains("7E00") -> "OK" to ""
                    compact.contains("NODATA") || compact.isEmpty() -> "NO DATA" to ""
                    else -> "OTHER" to ""
                }
            }
            is ObdResult.Failure -> res.reason.uppercase() to ""
        }

        fun nrcName(code: String): String = when (code) {
            "11" -> "service not supported"
            "12" -> "sub-function not supported"
            "13" -> "wrong length"
            "22" -> "conditions not correct"
            "31" -> "DID not supported"
            "33" -> "security access denied"
            "78" -> "response pending"
            "7E", "7F" -> "not supported in this session"
            else -> ""
        }
    }
}
