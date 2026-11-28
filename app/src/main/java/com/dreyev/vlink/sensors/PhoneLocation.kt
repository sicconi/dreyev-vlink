package com.dreyev.vlink.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.dreyev.vlink.obd.GpsFix
import com.dreyev.vlink.obd.Reading
import com.dreyev.vlink.obd.Source
import com.dreyev.vlink.obd.VehicleStore

/** Phone GNSS: position, GPS speed, heading, altitude and satellite count. */
@SuppressLint("MissingPermission")
class PhoneLocation(context: Context, private val store: VehicleStore) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var sats: Int? = null
    private var lastSpeed: Double? = null
    private var lastT = 0L
    private var running = false

    /**
     * Positions driven this session, for the map trace. A point is added once the car has moved
     * at least [MIN_STEP_M] from the previous one; the oldest points drop after [MAX_POINTS].
     */
    val trail = ArrayDeque<Pair<Double, Double>>()

    /** Forget the trace (Map tab "Clear"). */
    fun clearTrail() = trail.clear()

    private val listener = LocationListener { loc -> onLocation(loc) }

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            sats = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }

    fun start() {
        if (running) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper())
            lm.registerGnssStatusCallback(gnss, android.os.Handler(Looper.getMainLooper()))
            running = true
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) { /* no GPS provider */ }
    }

    fun stop() {
        if (!running) return
        lm.removeUpdates(listener)
        lm.unregisterGnssStatusCallback(gnss)
        running = false
    }

    private fun onLocation(l: Location) {
        val t = System.currentTimeMillis()
        val kmh = if (l.hasSpeed()) l.speed * 3.6 else null
        var accel: Double? = null
        if (kmh != null) {
            val prev = lastSpeed
            val dt = (t - lastT) / 1000.0
            if (prev != null && dt in 0.2..3.0) accel = (kmh - prev) / 3.6 / dt
            lastSpeed = kmh; lastT = t
        }
        val prev = trail.lastOrNull()
        if (prev == null || distanceM(prev.first, prev.second, l.latitude, l.longitude) >= MIN_STEP_M) {
            trail.addLast(l.latitude to l.longitude)
            while (trail.size > MAX_POINTS) trail.removeFirst()
        }
        val fix = GpsFix(
            lat = l.latitude, lon = l.longitude,
            altitudeM = if (l.hasAltitude()) l.altitude else null,
            speedKmh = kmh,
            headingDeg = if (l.hasBearing()) l.bearing.toDouble() else null,
            accuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else null,
            satellites = sats,
        )
        store.update {
            it.copy(
                gps = Reading(fix, Source.PHONE, "GNSS", t),
                gpsAccelMs2 = accel?.let { a -> Reading(a, Source.PHONE, "GNSS", t) } ?: it.gpsAccelMs2,
            )
        }
    }

    companion object {
        const val MIN_STEP_M = 3.0
        const val MAX_POINTS = 20_000   // ≥ 60 km of trace

        /** Equirectangular distance; plenty for points a few meters apart. */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2))
            val y = Math.toRadians(lat2 - lat1)
            return Math.sqrt(x * x + y * y) * 6_371_000.0
        }

        /** Length of a trace in meters. */
        fun lengthM(points: List<Pair<Double, Double>>): Double =
            points.zipWithNext { a, b -> distanceM(a.first, a.second, b.first, b.second) }.sum()
    }
}
