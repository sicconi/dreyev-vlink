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

    /** Recent positions for the breadcrumb trail on the GPS card. */
    val trail = ArrayDeque<Pair<Double, Double>>()

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
        trail.addLast(l.latitude to l.longitude)
        while (trail.size > 300) trail.removeFirst()
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
}
