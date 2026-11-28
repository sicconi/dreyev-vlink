package com.dreyev.vlink.data

import android.content.Context
import com.dreyev.vlink.obd.VehicleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs every signal (metric units) to a CSV at 5 Hz.
 * Files: Android/data/com.dreyev.vlink/files/trips/ (reachable over USB).
 */
class TripRecorder(private val context: Context, private val store: VehicleStore, private val scope: CoroutineScope) {
    private var job: Job? = null
    private val _file = MutableStateFlow<File?>(null)
    val recordingFile: StateFlow<File?> = _file

    fun toggle() = if (job?.isActive == true) stop() else start()

    fun start() {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "trips").apply { mkdirs() }
        val f = File(dir, "trip-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv")
        _file.value = f
        job = scope.launch(Dispatchers.IO) {
          try {
            f.bufferedWriter().use { w ->
                w.write(
                    "time_ms,speed_kmh,rpm,load_pct,pedal_pct,brake_on,brake_bar,steer_deg,long_g,long_src,lat_g,vert_g,speed_long_g," +
                        "fl_kpa,fr_kpa,rl_kpa,rr_kpa,fl_c,fr_c,rl_c,rr_c,ws_fl,ws_fr,ws_rl,ws_rr,ambient_c,odo_km,lat,lon,alt_m,gps_kmh,heading\n",
                )
                while (isActive) {
                    val s = store.state.value
                    val t = s.tires
                    val g = s.gps?.value
                    fun n(v: Any?) = v?.toString() ?: ""
                    val lon = s.longitudinal()
                    val row = listOf(
                        System.currentTimeMillis(), s.speedKmh?.value, s.rpm?.value, s.engineLoadPct?.value, s.pedalPct?.value,
                        s.brakePedalOn?.value, s.brakePressureBar?.value, s.steeringDeg?.value, lon?.value, lon?.source,
                        s.lateral()?.value, s.vertical()?.value, s.speedDerivedLongG?.value,
                        t.values.toList().getOrNull(0)?.pressureKpa?.value, t.values.toList().getOrNull(1)?.pressureKpa?.value,
                        t.values.toList().getOrNull(2)?.pressureKpa?.value, t.values.toList().getOrNull(3)?.pressureKpa?.value,
                        t.values.toList().getOrNull(0)?.tempC?.value, t.values.toList().getOrNull(1)?.tempC?.value,
                        t.values.toList().getOrNull(2)?.tempC?.value, t.values.toList().getOrNull(3)?.tempC?.value,
                        t.values.toList().getOrNull(0)?.wheelSpeedKmh?.value, t.values.toList().getOrNull(1)?.wheelSpeedKmh?.value,
                        t.values.toList().getOrNull(2)?.wheelSpeedKmh?.value, t.values.toList().getOrNull(3)?.wheelSpeedKmh?.value,
                        s.ambientC?.value, s.odometerKm?.value, g?.lat, g?.lon, g?.altitudeM, g?.speedKmh, g?.headingDeg,
                    ).joinToString(",") { n(it) }
                    w.write(row); w.newLine()
                    delay(200)
                }
            }
          } finally {
            // Writer is closed here; put a copy where the user can find and attach it.
            _lastSaved.value = Downloads.save(context, f)
          }
        }
    }

    private val _lastSaved = MutableStateFlow<String?>(null)
    /** Downloads path of the last finished trip, for the UI. */
    val lastSaved: StateFlow<String?> = _lastSaved

    fun stop() {
        job?.cancel(); job = null
        _file.value = null
    }
}
