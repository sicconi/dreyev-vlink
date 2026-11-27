package com.dreyev.vlink.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.dreyev.vlink.obd.Accel3
import com.dreyev.vlink.obd.Reading
import com.dreyev.vlink.obd.Source
import com.dreyev.vlink.obd.VehicleStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/** Mount orientation: unit vectors in phone coordinates. */
data class MountCalibration(val up: FloatArray, val forward: FloatArray) {
    fun encode() = (up + forward).joinToString(",")

    companion object {
        fun decode(s: String?): MountCalibration? {
            val v = s?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: return null
            return if (v.size == 6) MountCalibration(v.subList(0, 3).toFloatArray(), v.subList(3, 6).toFloatArray()) else null
        }
    }
}

enum class CalStep { IDLE, HOLD_STILL, BRAKE_GENTLY, DONE, FAILED }

/**
 * Phone IMU turned into vehicle axes (longitudinal, lateral, vertical) in g.
 * Without calibration it assumes the screen faces the driver (works for most dash mounts
 * in portrait or landscape). Calibration: car still (finds "up"), then a gentle
 * straight-line stop (finds "forward"), so the phone can sit at any angle.
 */
class PhoneMotion(context: Context, private val store: VehicleStore) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gravity = floatArrayOf(0f, 9.81f, 0f)
    private val lin = FloatArray(3)
    private val filt = DoubleArray(3)
    private var lastEmit = 0L
    var calibration: MountCalibration? = null

    private val _calStep = MutableStateFlow(CalStep.IDLE)
    val calStep: StateFlow<CalStep> = _calStep
    var onCalibrated: (MountCalibration) -> Unit = {}
    private val calSamples = mutableListOf<FloatArray>()
    private var calStart = 0L
    private var calUp: FloatArray? = null

    fun start() {
        val rate = SensorManager.SENSOR_DELAY_GAME
        if (linear != null && gravitySensor != null) {
            sm.registerListener(this, linear, rate); sm.registerListener(this, gravitySensor, rate)
        } else if (accel != null) {
            sm.registerListener(this, accel, rate)
        }
    }

    fun stop() = sm.unregisterListener(this)

    fun beginCalibration() {
        calSamples.clear(); calUp = null
        calStart = System.currentTimeMillis()
        _calStep.value = CalStep.HOLD_STILL
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> { System.arraycopy(e.values, 0, gravity, 0, 3); return }
            Sensor.TYPE_LINEAR_ACCELERATION -> System.arraycopy(e.values, 0, lin, 0, 3)
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) {
                    gravity[i] = 0.9f * gravity[i] + 0.1f * e.values[i]
                    lin[i] = e.values[i] - gravity[i]
                }
            }
        }
        calibrationStep()
        process()
    }

    private fun process() {
        val cal = calibration
        val up = cal?.up ?: norm(gravity)
        // Default forward: out of the back of the phone (screen faces the driver), made horizontal.
        val fwd = cal?.forward ?: norm(reject(floatArrayOf(0f, 0f, -1f), up))
        val right = norm(cross(fwd, up))
        val g = SensorManager.GRAVITY_EARTH
        val longG = dot(lin, fwd) / g
        val latG = dot(lin, right) / g
        val vertG = (dot(lin, up) + g) / g
        val a = 0.15
        filt[0] += a * (longG - filt[0]); filt[1] += a * (latG - filt[1]); filt[2] += a * (vertG - filt[2])
        val now = System.currentTimeMillis()
        if (now - lastEmit >= 50) {
            lastEmit = now
            store.update { it.copy(phoneAccel = Reading(Accel3(filt[0], filt[1], filt[2]), Source.PHONE, "IMU", now)) }
        }
    }

    private fun calibrationStep() {
        val now = System.currentTimeMillis()
        when (_calStep.value) {
            CalStep.HOLD_STILL -> {
                calSamples += gravity.copyOf()
                if (now - calStart > 2_000) {
                    val avg = FloatArray(3)
                    calSamples.forEach { for (i in 0..2) avg[i] += it[i] / calSamples.size }
                    calUp = norm(avg); calSamples.clear(); calStart = now
                    _calStep.value = CalStep.BRAKE_GENTLY
                }
            }
            CalStep.BRAKE_GENTLY -> {
                val up = calUp ?: return
                val h = reject(lin, up)
                if (len(h) > 0.8f) calSamples += h.copyOf()    // > ~0.08 g of horizontal accel
                if (calSamples.size > 40) {
                    val avg = FloatArray(3)
                    calSamples.forEach { for (i in 0..2) avg[i] += it[i] }
                    // Braking accelerates the car backwards, so forward is the opposite direction.
                    val fwd = norm(floatArrayOf(-avg[0], -avg[1], -avg[2]))
                    val c = MountCalibration(up, fwd)
                    calibration = c
                    onCalibrated(c)
                    _calStep.value = CalStep.DONE
                } else if (now - calStart > 20_000) {
                    _calStep.value = CalStep.FAILED
                }
            }
            else -> Unit
        }
    }

    private fun dot(a: FloatArray, b: FloatArray) = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).toDouble()
    private fun len(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    private fun norm(a: FloatArray): FloatArray { val l = len(a).takeIf { it > 1e-6f } ?: 1f; return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
    private fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun reject(v: FloatArray, n: FloatArray): FloatArray {
        val d = dot(v, n).toFloat(); return floatArrayOf(v[0] - d * n[0], v[1] - d * n[1], v[2] - d * n[2])
    }
}
