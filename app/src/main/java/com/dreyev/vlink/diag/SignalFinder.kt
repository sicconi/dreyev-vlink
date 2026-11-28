package com.dreyev.vlink.diag

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.dreyev.vlink.VLinkApp
import com.dreyev.vlink.obd.Elm327
import com.dreyev.vlink.obd.ObdTransport
import com.dreyev.vlink.obd.Prober
import com.dreyev.vlink.obd.SimulatedElm327
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A byte that changed while watching, with the action that was tagged at the time. */
data class ByteChange(
    val timeMs: Long, val target: String, val index: Int, val from: Int, val to: Int, val marker: String,
) {
    /** Torque-style letter for the byte (A = first byte after the 62xxxx prefix). */
    val letter: String get() = if (index < 26) ('A' + index).toString() else "" + ('A' + index / 26 - 1) + ('A' + index % 26)
}

data class FinderState(
    val running: Boolean = false,
    val step: String = "",
    val progress: Float = 0f,
    val profileRows: List<Prober.ProbeRow> = emptyList(),
    val found: List<Prober.ProbeRow> = emptyList(),
    val changes: List<ByteChange> = emptyList(),
    val watchCount: Int = 0,
    val file: File? = null,
    val error: String? = null,
)

/**
 * Runs the [Prober] on the real adapter: pauses the dashboard's link, opens its own
 * connection, logs every request/reply to a CSV, then hands the adapter back.
 */
class SignalFinder(private val app: VLinkApp) {
    private val _state = MutableStateFlow(FinderState())
    val state: StateFlow<FinderState> = _state

    /** What the driver is doing right now; stamped on every watch row. */
    val marker = MutableStateFlow("baseline (nothing pressed)")

    private var job: Job? = null
    private var out: PrintWriter? = null

    enum class Mode { CHECK_PROFILE, SCAN, WATCH }

    fun start(mode: Mode) {
        if (job?.isActive == true) return
        val factory = transportFactory() ?: run {
            _state.update { it.copy(error = "Pick your VLink adapter in Settings first.") }
            return
        }
        _state.update { it.copy(running = true, error = null, step = "Connecting to the adapter…", progress = 0f, changes = if (mode == Mode.WATCH) emptyList() else it.changes) }
        job = app.scope.launch {
            app.pauseLink()
            val transport = factory()
            try {
                openLog()
                transport.open()
                val elm = Elm327(transport) { Log.d("VLinkFinder", it) }
                val info = elm.initialize()
                log("# adapter ${info.version}, ${info.protocol}, ${info.voltage ?: "?"} V, mode $mode")
                val prober = Prober(elm, { row -> out?.println(row.csv()) })
                when (mode) {
                    Mode.CHECK_PROFILE -> checkProfile(prober)
                    Mode.SCAN -> scan(prober)
                    Mode.WATCH -> watch(prober)
                }
                _state.update { it.copy(step = "Done", progress = 1f) }
            } catch (e: CancellationException) {
                _state.update { it.copy(step = "Stopped") }
            } catch (e: Exception) {
                Log.w("VLinkFinder", "finder failed", e)
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                out?.flush(); out?.close(); out = null
                try { transport.close() } catch (_: Exception) {}
                _state.update { it.copy(running = false) }
                app.resumeLink()
            }
        }
    }

    fun stop() { job?.cancel() }

    private fun transportFactory(): (() -> ObdTransport)? =
        if (app.isSimulated) ({ SimulatedElm327() })
        else app.settings.value.device?.let { app.transportFor(it) }

    private suspend fun checkProfile(p: Prober) {
        val profile = app.currentProfile()
        log("# profile ${profile.id}")
        val rows = p.checkProfile(profile) { i, n ->
            _state.update { it.copy(step = "Checking ${profile.name}: request ${minOf(i + 1, n)} of $n", progress = if (n == 0) 1f else i.toFloat() / n) }
        }
        _state.update { it.copy(profileRows = rows) }
    }

    private suspend fun scan(p: Prober) {
        val found = p.scan(Prober.EGMP_ECUS, Prober::defaultDids) { step, i, n ->
            _state.update { it.copy(step = step, progress = i.toFloat() / n) }
        }
        _state.update { it.copy(found = found) }
    }

    private suspend fun watch(p: Prober) {
        val fromScan = _state.value.found.filter { it.header in Prober.WATCH_ECUS }.map { Prober.Target(it.header, it.request) }
        val targets = fromScan.ifEmpty { KNOWN_BODY_TARGETS }
        log("# watching ${targets.size} DIDs" + if (fromScan.isEmpty()) " (known list; run a scan first for more)" else "")
        val last = HashMap<String, IntArray>()
        var n = 0
        p.watch(targets, { marker.value }) { row ->
            n++
            if (row.ok) {
                val now = row.bytes
                val prev = last.put(row.request.let { "${row.header} $it" }, now)
                if (prev != null) {
                    val diffs = (0 until minOf(prev.size, now.size)).filter { prev[it] != now[it] }
                        .map { ByteChange(row.timeMs, "${row.header} ${row.request}", it, prev[it], now[it], row.marker) }
                    if (diffs.isNotEmpty()) _state.update { s -> s.copy(changes = (diffs + s.changes).take(200)) }
                }
            }
            _state.update { it.copy(step = "Watching ${targets.size} DIDs · ${n} reads", watchCount = n) }
        }
    }

    private fun openLog() {
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "signal-finder").apply { mkdirs() }
        val f = File(dir, "vlink-finder-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv")
        out = PrintWriter(f.bufferedWriter()).also { it.println(Prober.ProbeRow.CSV_HEADER) }
        _state.update { it.copy(file = f) }
    }

    private fun log(line: String) { out?.println(line) }

    /** Share the latest finder CSV (and optionally the latest trip CSV) with any app. */
    fun share(context: Context, files: List<File>) {
        val uris = files.filter { it.exists() }.map { FileProvider.getUriForFile(context, context.packageName + ".files", it) }
        if (uris.isEmpty()) return
        val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            putExtra(Intent.EXTRA_SUBJECT, "dreyev VLink log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun latestTrip(): File? =
        File(app.getExternalFilesDir(null) ?: app.filesDir, "trips").listFiles()?.maxByOrNull { it.lastModified() }

    companion object {
        /** Body/chassis DIDs known to answer on 2022–2024 Ioniq 5 (OBDb), watched when no scan has run. */
        val KNOWN_BODY_TARGETS = listOf(
            "770" to "22BC03", "770" to "22BC06", "770" to "22BC10",
            "7A0" to "22B008", "7A0" to "22B00C",
            "730" to "22F010", "7D1" to "220104", "7D4" to "220101",
            "7E2" to "22E004", "7C6" to "22B002",
        ).map { (h, r) -> Prober.Target(h, r) }

        /** Actions offered as markers on the watch screen. */
        val MARKERS = listOf(
            "baseline (nothing pressed)",
            "left signal on", "right signal on", "hazards on",
            "driver belt buckled", "driver belt unbuckled",
            "passenger belt buckled", "passenger belt unbuckled",
            "brake light press", "brake firm press", "brake released",
            "steering full left", "steering full right",
        )
    }
}
