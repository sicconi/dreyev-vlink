package com.dreyev.vlink.auto

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.dreyev.vlink.obd.Corner
import com.dreyev.vlink.obd.EngineState
import com.dreyev.vlink.obd.Reading
import com.dreyev.vlink.obd.Source
import com.dreyev.vlink.obd.UnitPrefs
import com.dreyev.vlink.obd.VehicleState
import com.dreyev.vlink.obd.Vin
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class CarPage(val title: String) { DRIVE("Drive"), TIRES("Tires"), VEHICLE("Vehicle") }

/**
 * Draws the dashboard on the Android Auto map surface with plain android.graphics, so the
 * car screen gets the same analog gauges as the phone (templates alone only allow text and
 * lists). Layout is designed on a 1200×560 virtual canvas and scaled to the visible area.
 */
class CarDashRenderer {
    private object K {
        val bg = 0xFF0B0E12.toInt(); val card = 0xFF141A21.toInt(); val inner = 0xFF10151B.toInt()
        val border = 0xFF222B35.toInt(); val track = 0xFF232C37.toInt(); val grid = 0xFF2A3440.toInt()
        val tick = 0xFF5B6876.toInt(); val wheel = 0xFF3A4552.toInt()
        val text = 0xFFE8EDF2.toInt(); val dim = 0xFFB4BEC8.toInt(); val muted = 0xFF9AA6B2.toInt(); val faint = 0xFF8C99A6.toInt()
        val obd = 0xFF4CC9F0.toInt(); val mfr = 0xFFF5A524.toInt(); val phone = 0xFFB7A6FF.toInt(); val est = 0xFFC9D1D9.toInt()
        val red = 0xFFFF6B4A.toInt(); val redSoft = 0xFFFF8A6E.toInt(); val redBg = 0xFF3A1A14.toInt()
        val green = 0xFF3DDC97.toInt(); val greenText = 0xFF5BE3A8.toInt(); val greenBg = 0xFF123A2B.toInt()
        fun src(s: Source?) = when (s) { Source.OBD -> obd; Source.MFR -> mfr; Source.PHONE -> phone; Source.EST -> est; null -> faint }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val regular = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    /** Draw [page] into [area] (the part of the surface not covered by car UI). */
    fun draw(c: Canvas, area: Rect, page: CarPage, s: VehicleState, u: UnitPrefs, trail: List<Pair<Double, Double>>) {
        c.drawColor(K.bg)
        val vw = 1200f; val vh = 560f
        val scale = min(area.width() / vw, area.height() / vh)
        c.save()
        c.translate(area.left + (area.width() - vw * scale) / 2, area.top + (area.height() - vh * scale) / 2)
        c.scale(scale, scale)
        header(c, s, page)
        when (page) {
            CarPage.DRIVE -> drive(c, s, u)
            CarPage.TIRES -> tires(c, s, u)
            CarPage.VEHICLE -> vehicle(c, s, u, trail)
        }
        c.restore()
    }

    // ---------------- header ----------------
    private fun header(c: Canvas, s: VehicleState, page: CarPage) {
        val (label, color) = when (s.link.state.name) {
            "CONNECTED" -> (if (s.link.simulated) "Demo mode" else "VLink connected") to K.green
            "DISCONNECTED" -> "Not connected · open the phone app to pick the adapter" to K.faint
            "ERROR" -> "Reconnecting…" to K.red
            else -> "Connecting…" to K.mfr
        }
        fill.color = color; c.drawCircle(28f, 26f, 7f, fill)
        text(c, label, 44f, 33f, 22f, K.text, bold)
        text(c, page.title.uppercase() + (s.profileName?.let { "  ·  $it" } ?: ""), 44f, 56f, 15f, K.faint)
        val pill = if (s.isEv) when (s.engine?.value) { EngineState.ON -> "READY"; EngineState.OFF -> "OFF"; else -> "READY N/A" }
        else when (s.engine?.value) { EngineState.ON -> "ENGINE ON"; EngineState.OFF -> "ENGINE OFF"; else -> "ENGINE N/A" }
        val on = s.engine?.value == EngineState.ON
        pill(c, pill, 1180f, 30f, if (on) K.greenText else K.dim, if (on) K.greenBg else K.track, rightAlign = true)
    }

    // ---------------- DRIVE ----------------
    private fun drive(c: Canvas, s: VehicleState, u: UnitPrefs) {
        // pedals
        val pedal = s.pedalPct?.value
        vbar(c, 40f, 120f, 46f, 330f, pedal?.div(100), K.obd, "GAS", pedal?.let { "${it.toInt()}%" }, s.pedalPct, Source.OBD)
        val bp = s.brakePressureBar?.value?.let { u.brakePressure(it) }
        vbar(c, 120f, 120f, 46f, 330f, bp?.div(u.brakePressureMax), K.red, "BRAKE",
            bp?.let { "${it.toInt()} ${u.brakePressureUnit}" } ?: s.brakePedalOn?.value?.let { if (it) "ON" else "OFF" }, s.brakePressureBar ?: s.brakePedalOn, Source.MFR)

        // turn signals + belts around the hero gauge
        arrow(c, 250f, 95f, true, s.turnLeft?.value)
        arrow(c, 905f, 95f, false, s.turnRight?.value)
        belt(c, 262f, 420f, s.beltDriver?.value, "Driver")
        belt(c, 885f, 420f, s.beltPassenger?.value, "Pass.")

        // hero gauge
        val speedKmh = s.speedKmh?.value ?: s.gps?.value?.speedKmh
        val speedTxt = speedKmh?.let { u.fmtSpeed(it) } ?: "N/A"
        if (s.isEv) {
            val kw = s.hvPowerKw?.value
            val sub = listOfNotNull(kw?.let { "%.0f kW".format(Locale.US, it) }, s.socPct?.value?.let { "SOC %.0f%%".format(Locale.US, it) }).joinToString(" · ")
            gauge(c, 600f, 300f, 215f, kw, -100.0, 250.0, 7, null, if ((kw ?: 0.0) < 0) K.green else K.obd, speedTxt, u.speedUnit, sub.ifEmpty { "Power N/A" }, "SPEED · POWER kW", s.speedKmh?.source, origin = 0.0)
        } else {
            val rpm = s.rpm?.value
            gauge(c, 600f, 300f, 215f, rpm?.div(1000), 0.0, 8.0, 8, 6.5, K.obd, speedTxt, u.speedUnit,
                rpm?.let { String.format(Locale.US, "%,d rpm", it.toLong()) } ?: "RPM N/A", "SPEED · RPM ×1000", s.speedKmh?.source)
        }

        // right column: G-force, steering, SOC/load
        card(c, 950f, 90f, 230f, 250f)
        label(c, "G-FORCE", 966f, 118f)
        val lon = s.longitudinal(); val lat = s.lateral()
        badge(c, lon?.source?.let { if (it == Source.MFR) "CAR ESC" else it.name } ?: "N/A", 1166f, 104f, K.src(lon?.source), right = true)
        friction(c, 1065f, 225f, 80f, lat?.value, lon?.value)
        text(c, "Lat " + UnitPrefs.signedG(lat?.value), 966f, 328f, 15f, K.muted)
        text(c, "Long " + UnitPrefs.signedG(lon?.value), 1166f, 328f, 15f, K.muted, align = Paint.Align.RIGHT)

        card(c, 950f, 355f, 110f, 175f)
        label(c, "STEER", 962f, 380f)
        steering(c, 1005f, 445f, 38f, s.steeringDeg?.value)
        text(c, UnitPrefs.steering(s.steeringDeg?.value), 1005f, 515f, 15f, if (s.steeringDeg == null) K.faint else K.text, bold, Paint.Align.CENTER)

        if (s.isEv) smallGauge(c, 1125f, 450f, 52f, s.socPct?.value, 0.0, 100.0, K.green, "SOC", "%", s.socPct?.source ?: Source.MFR)
        else smallGauge(c, 1125f, 450f, 52f, s.engineLoadPct?.value, 0.0, 100.0, K.obd, "LOAD", "%", s.engineLoadPct?.source ?: Source.OBD)

        // bottom line
        text(c, "Outside " + u.fmtTemp(s.ambientC?.value), 40f, 540f, 18f, K.dim)
        text(c, "Vertical " + (s.vertical()?.value?.let { UnitPrefs.fmt(it, 2) + " g" } ?: "N/A"), 260f, 540f, 18f, K.dim)
        text(c, "Odometer " + (s.odometerKm?.value?.let { u.fmtDistance(it) + " " + u.distanceUnit } ?: "N/A"), 880f, 540f, 18f, K.dim)
    }

    // ---------------- TIRES ----------------
    private fun tires(c: Canvas, s: VehicleState, u: UnitPrefs) {
        val now = System.currentTimeMillis()
        val pos = mapOf(Corner.FL to (30f to 90f), Corner.FR to (430f to 90f), Corner.RL to (30f to 320f), Corner.RR to (430f to 320f))
        // car outline
        fill.color = K.card; c.drawRoundRect(RectF(300f, 95f, 410f, 525f), 40f, 40f, fill)
        stroke.color = K.wheel; stroke.strokeWidth = 2f; c.drawRoundRect(RectF(300f, 95f, 410f, 525f), 40f, 40f, stroke)
        for ((corner, p) in pos) {
            val t = s.tires[corner]
            val kpa = t?.pressureKpa?.value
            val low = kpa != null && kpa < 220.0
            fill.color = if (low) K.mfr else 0xFF4A5663.toInt()
            val tx = if (corner == Corner.FL || corner == Corner.RL) 290f else 408f
            val ty = if (corner == Corner.FL || corner == Corner.FR) 150f else 400f
            c.drawRoundRect(RectF(tx, ty, tx + 12f, ty + 70f), 4f, 4f, fill)

            val (x, y) = p
            card(c, x, y, 250f, 210f, if (low) K.mfr else K.border)
            label(c, corner.label.uppercase(), x + 16f, y + 30f)
            badge(c, "TPMS·" + (t?.pressureKpa?.source?.name ?: "MFR"), x + 234f, y + 16f, if (t?.pressureKpa == null) K.faint else K.mfr, right = true)
            text(c, u.fmtTire(kpa), x + 16f, y + 92f, 52f, if (kpa == null) K.faint else if (low) 0xFFF5B94A.toInt() else K.text, bold)
            text(c, u.tirePressureUnit + if (low) "  LOW" else "", x + 16f + measure(u.fmtTire(kpa), 52f, bold) + 8f, y + 92f, 18f, if (low) K.mfr else K.muted, bold)
            text(c, u.fmtTemp(t?.tempC?.value) + " tire temp", x + 16f, y + 128f, 19f, if (t?.tempC == null) K.faint else K.dim)
            val age = t?.pressureKpa?.let { ((now - it.timeMs) / 1000).let { a -> if (a < 3) "live" else if (a < 60) "${a}s ago" else "${a / 60} min ago" } }
            text(c, "Updated " + (age ?: "—"), x + 16f, y + 156f, 15f, K.faint)
            text(c, "Wheel " + (t?.wheelSpeedKmh?.value?.let { u.fmtSpeed(it, 1) + " " + u.speedUnit } ?: "N/A"), x + 16f, y + 190f, 16f, K.muted)
        }
        // brake + accel
        card(c, 720f, 90f, 460f, 200f)
        val bp = s.brakePressureBar?.value?.let { u.brakePressure(it) }
        smallGauge(c, 810f, 190f, 70f, bp, 0.0, u.brakePressureMax, K.red, "BRAKE", u.brakePressureUnit, s.brakePressureBar?.source ?: Source.MFR)
        val on = s.brakePedalOn?.value
        pill(c, "PEDAL " + when (on) { true -> "ON"; false -> "OFF"; null -> "N/A" }, 910f, 120f, if (on == true) K.redSoft else K.dim, if (on == true) K.redBg else K.track)
        text(c, "Front caliper " + u.fmtForce(s.caliperFrontKn?.value), 910f, 190f, 17f, K.muted)
        hbar(c, 910f, 202f, 250f, s.caliperFrontKn?.value?.let { u.force(it) / u.forceMax }, K.red)
        text(c, "Rear caliper " + u.fmtForce(s.caliperRearKn?.value), 910f, 245f, 17f, K.muted)
        hbar(c, 910f, 257f, 250f, s.caliperRearKn?.value?.let { u.force(it) / u.forceMax }, K.red)

        card(c, 720f, 305f, 460f, 225f)
        label(c, "ACCELERATION (±1 g)", 736f, 335f)
        val v = s.vertical()
        axis(c, 345f + 20f, "Longitudinal", s.longitudinal())
        axis(c, 345f + 65f, "Lateral", s.lateral())
        axis(c, 345f + 110f, "Vertical Δ1g", v?.let { Reading(it.value - 1.0, it.source, it.tag, it.timeMs) })
        axis(c, 345f + 155f, "From speed", s.speedDerivedLongG)
    }

    // ---------------- VEHICLE ----------------
    private fun vehicle(c: Canvas, s: VehicleState, u: UnitPrefs, trail: List<Pair<Double, Double>>) {
        card(c, 20f, 90f, 560f, 130f)
        label(c, "VIN", 36f, 120f); badge(c, s.vin?.label ?: "OBD 09 02", 564f, 106f, K.src(s.vin?.source), right = true)
        text(c, s.vin?.value ?: "N/A", 36f, 170f, 36f, if (s.vin == null) K.faint else K.text, mono)
        text(c, s.vin?.value?.let { Vin.describe(it) } ?: "Read once on connect", 36f, 202f, 16f, K.faint)

        card(c, 20f, 235f, 560f, 120f)
        label(c, "ODOMETER", 36f, 265f); badge(c, s.odometerKm?.label ?: "OBD A6", 564f, 251f, K.src(s.odometerKm?.source), right = true)
        text(c, s.odometerKm?.value?.let { u.fmtDistance(it) + " " + u.distanceUnit } ?: "N/A", 36f, 330f, 46f, if (s.odometerKm == null) K.faint else K.text, mono)

        card(c, 20f, 370f, 270f, 160f)
        label(c, "OUTSIDE", 36f, 400f)
        text(c, u.fmtTemp(s.ambientC?.value), 36f, 470f, 52f, if (s.ambientC == null) K.faint else K.text, bold)
        text(c, s.ambientC?.label ?: "N/A", 36f, 510f, 14f, K.src(s.ambientC?.source), bold)

        card(c, 310f, 370f, 270f, 160f)
        if (s.isEv) {
            label(c, "BATTERY", 326f, 400f)
            text(c, s.socPct?.value?.let { "%.0f%%".format(Locale.US, it) } ?: "N/A", 326f, 470f, 52f, if (s.socPct == null) K.faint else K.greenText, bold)
            text(c, listOfNotNull(s.hvVoltageV?.value?.let { "%.0f V".format(Locale.US, it) }, s.hvPowerKw?.value?.let { "%.1f kW".format(Locale.US, it) }).joinToString(" · ").ifEmpty { "MFR" }, 326f, 510f, 15f, K.faint)
        } else {
            label(c, "ENGINE", 326f, 400f)
            val st = s.engine?.value
            text(c, when (st) { EngineState.ON -> "ON"; EngineState.OFF -> "OFF"; else -> "N/A" }, 326f, 470f, 52f, if (st == EngineState.ON) K.greenText else K.faint, bold)
            text(c, "Run time " + (s.runTimeS?.value?.let { UnitPrefs.duration(it) } ?: "N/A"), 326f, 510f, 15f, K.faint)
        }

        // GPS
        val g = s.gps?.value
        card(c, 600f, 90f, 580f, 440f)
        label(c, "GPS", 616f, 120f)
        badge(c, if (g == null) "PHONE · no fix" else listOfNotNull("PHONE GNSS", g.satellites?.let { "$it sats" }, g.accuracyM?.let { "±${it.toInt()} m" }).joinToString(" · "), 1164f, 106f, if (g == null) K.faint else K.phone, right = true)
        val map = RectF(616f, 140f, 1164f, 360f)
        fill.color = K.inner; c.drawRoundRect(map, 14f, 14f, fill)
        drawTrail(c, map, trail)
        compass(c, 680f, 440f, 52f, g?.headingDeg)
        val la = g?.lat?.let { String.format(Locale.US, "%.5f° %s", abs(it), if (it >= 0) "N" else "S") } ?: "N/A"
        val lo = g?.lon?.let { String.format(Locale.US, "%.5f° %s", abs(it), if (it >= 0) "E" else "W") } ?: "N/A"
        kv(c, "Lat", la, 760f, 400f); kv(c, "Long", lo, 960f, 400f)
        kv(c, "Speed", g?.speedKmh?.let { u.fmtSpeed(it, 1) + " " + u.speedUnit } ?: "N/A", 760f, 470f)
        kv(c, "Altitude", g?.altitudeM?.let { u.fmtAltitude(it) } ?: "N/A", 960f, 470f)
    }

    // ---------------- primitives ----------------
    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface = regular, align: Paint.Align = Paint.Align.LEFT) {
        txt.textSize = size; txt.color = color; txt.typeface = tf; txt.textAlign = align
        c.drawText(s, x, y, txt)
    }

    private fun measure(s: String, size: Float, tf: Typeface): Float { txt.textSize = size; txt.typeface = tf; return txt.measureText(s) }

    private fun label(c: Canvas, s: String, x: Float, y: Float) = text(c, s, x, y, 15f, K.dim, bold)

    private fun card(c: Canvas, x: Float, y: Float, w: Float, h: Float, border: Int = K.border) {
        val r = RectF(x, y, x + w, y + h)
        fill.color = K.card; c.drawRoundRect(r, 20f, 20f, fill)
        stroke.color = border; stroke.strokeWidth = 1.5f; c.drawRoundRect(r, 20f, 20f, stroke)
    }

    private fun badge(c: Canvas, s: String, x: Float, y: Float, color: Int, right: Boolean = false) {
        val w = measure(s, 13f, bold) + 14f
        val left = if (right) x - w else x
        stroke.color = color; stroke.strokeWidth = 1.5f
        c.drawRoundRect(RectF(left, y, left + w, y + 22f), 5f, 5f, stroke)
        text(c, s, left + 7f, y + 16f, 13f, color, bold)
    }

    private fun pill(c: Canvas, s: String, x: Float, y: Float, fg: Int, bg: Int, rightAlign: Boolean = false) {
        val w = measure(s, 17f, bold) + 28f
        val left = if (rightAlign) x - w else x
        fill.color = bg; c.drawRoundRect(RectF(left, y - 18f, left + w, y + 18f), 18f, 18f, fill)
        text(c, s, left + 14f, y + 6f, 17f, fg, bold)
    }

    private fun arcRect(cx: Float, cy: Float, r: Float) = RectF(cx - r, cy - r, cx + r, cy + r)

    /** 270° gauge; angles measured like the phone gauge (start at 7:30, clockwise). */
    private fun gauge(
        c: Canvas, cx: Float, cy: Float, r: Float, value: Double?, min: Double, max: Double, ticks: Int, redFrom: Double?,
        color: Int, display: String, unit: String, sub: String, title: String, source: Source?, origin: Double = min,
    ) {
        val f = value?.let { ((it - min) / (max - min)).coerceIn(0.0, 1.0).toFloat() }
        val f0 = ((origin - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
        val start = 135f; val sweep = 270f
        stroke.strokeWidth = r * 0.14f; stroke.color = K.track
        c.drawArc(arcRect(cx, cy, r * 0.8f), start, sweep, false, stroke)
        if (redFrom != null) {
            val rf = ((redFrom - min) / (max - min)).toFloat()
            stroke.strokeWidth = r * 0.05f; stroke.color = K.red; stroke.strokeCap = Paint.Cap.BUTT
            c.drawArc(arcRect(cx, cy, r * 0.92f), start + sweep * rf, sweep * (1 - rf), false, stroke)
            stroke.strokeCap = Paint.Cap.ROUND
        }
        if (f != null && abs(f - f0) > 0.003f) {
            stroke.strokeWidth = r * 0.14f; stroke.color = color
            c.drawArc(arcRect(cx, cy, r * 0.8f), start + sweep * min(f, f0), sweep * abs(f - f0), false, stroke)
        }
        stroke.color = K.tick; stroke.strokeWidth = 2.5f
        for (i in 0..ticks) {
            val a = Math.toRadians((start + sweep * i / ticks).toDouble())
            c.drawLine(cx + (r * 0.66f * cos(a)).toFloat(), cy + (r * 0.66f * sin(a)).toFloat(), cx + (r * 0.55f * cos(a)).toFloat(), cy + (r * 0.55f * sin(a)).toFloat(), stroke)
        }
        if (f != null) {
            val a = Math.toRadians((start + sweep * f).toDouble())
            stroke.color = Color.WHITE; stroke.strokeWidth = 5f
            c.drawLine(cx + (r * 0.5f * cos(a)).toFloat(), cy + (r * 0.5f * sin(a)).toFloat(), cx + (r * 0.74f * cos(a)).toFloat(), cy + (r * 0.74f * sin(a)).toFloat(), stroke)
        }
        text(c, display, cx, cy + r * 0.12f, r * 0.42f, if (value == null && display == "N/A") K.faint else K.text, bold, Paint.Align.CENTER)
        text(c, unit, cx, cy + r * 0.3f, r * 0.11f, K.muted, regular, Paint.Align.CENTER)
        text(c, sub, cx, cy + r * 0.48f, r * 0.09f, K.muted, regular, Paint.Align.CENTER)
        text(c, title, cx, cy + r + 14f, 16f, K.dim, bold, Paint.Align.CENTER)
        val b = source?.name ?: "N/A"
        badge(c, b, cx - (measure(b, 13f, bold) + 14f) / 2, cy + r + 24f, K.src(source))
    }

    private fun smallGauge(c: Canvas, cx: Float, cy: Float, r: Float, v: Double?, min: Double, max: Double, color: Int, title: String, unit: String, src: Source?) {
        val f = v?.let { ((it - min) / (max - min)).coerceIn(0.0, 1.0).toFloat() }
        stroke.strokeWidth = r * 0.2f; stroke.color = K.track
        c.drawArc(arcRect(cx, cy, r), 135f, 270f, false, stroke)
        if (f != null && f > 0.003f) { stroke.color = color; c.drawArc(arcRect(cx, cy, r), 135f, 270f * f, false, stroke) }
        text(c, v?.let { "%.0f".format(Locale.US, it) } ?: "N/A", cx, cy + r * 0.18f, r * (if (v == null) 0.4f else 0.55f), if (v == null) K.faint else K.text, bold, Paint.Align.CENTER)
        text(c, unit, cx, cy + r * 0.55f, r * 0.24f, K.muted, regular, Paint.Align.CENTER)
        text(c, title, cx, cy + r + 22f, 14f, K.dim, bold, Paint.Align.CENTER)
        text(c, src?.name ?: "", cx, cy + r + 40f, 12f, K.src(src), bold, Paint.Align.CENTER)
    }

    private fun vbar(c: Canvas, x: Float, y: Float, w: Float, h: Float, f: Double?, color: Int, title: String, value: String?, r: Reading<*>?, fb: Source) {
        text(c, value ?: "N/A", x + w / 2, y - 12f, 17f, if (value == null) K.faint else K.text, bold, Paint.Align.CENTER)
        fill.color = K.track; c.drawRoundRect(RectF(x, y, x + w, y + h), 14f, 14f, fill)
        if (f != null) { val fh = h * f.coerceIn(0.0, 1.0).toFloat(); fill.color = color; c.drawRoundRect(RectF(x, y + h - fh, x + w, y + h), 14f, 14f, fill) }
        text(c, title, x + w / 2, y + h + 26f, 15f, K.dim, bold, Paint.Align.CENTER)
        text(c, (r?.source ?: fb).name, x + w / 2, y + h + 46f, 13f, if (r == null) K.faint else K.src(r.source), bold, Paint.Align.CENTER)
    }

    private fun hbar(c: Canvas, x: Float, y: Float, w: Float, f: Double?, color: Int) {
        fill.color = K.track; c.drawRoundRect(RectF(x, y, x + w, y + 10f), 5f, 5f, fill)
        if (f != null) { fill.color = color; c.drawRoundRect(RectF(x, y, x + w * f.coerceIn(0.0, 1.0).toFloat(), y + 10f), 5f, 5f, fill) }
    }

    private fun axis(c: Canvas, y: Float, name: String, r: Reading<Double>?) {
        text(c, name, 736f, y + 6f, 17f, K.dim)
        val x0 = 880f; val w = 200f
        fill.color = K.track; c.drawRoundRect(RectF(x0, y - 6f, x0 + w, y + 6f), 6f, 6f, fill)
        r?.value?.let {
            val v = it.coerceIn(-1.0, 1.0).toFloat(); val mid = x0 + w / 2
            fill.color = if (r.source == Source.EST) K.est else K.phone
            c.drawRoundRect(RectF(if (v >= 0) mid else mid + v * w / 2, y - 6f, if (v >= 0) mid + v * w / 2 else mid, y + 6f), 6f, 6f, fill)
        }
        text(c, UnitPrefs.signedG(r?.value), 1164f, y + 6f, 17f, if (r == null) K.faint else K.text, bold, Paint.Align.RIGHT)
    }

    private fun friction(c: Canvas, cx: Float, cy: Float, r: Float, lat: Double?, lon: Double?) {
        fill.color = K.inner; c.drawCircle(cx, cy, r, fill)
        stroke.color = K.grid; stroke.strokeWidth = 2f; c.drawCircle(cx, cy, r, stroke)
        stroke.pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f); c.drawCircle(cx, cy, r / 2, stroke); stroke.pathEffect = null
        c.drawLine(cx - r, cy, cx + r, cy, stroke); c.drawLine(cx, cy - r, cx, cy + r, stroke)
        if (lat != null && lon != null) {
            val x = cx + (lat.coerceIn(-1.0, 1.0) * r).toFloat(); val y = cy - (lon.coerceIn(-1.0, 1.0) * r).toFloat()
            stroke.color = K.phone; stroke.strokeWidth = 3f; c.drawLine(cx, cy, x, y, stroke)
            fill.color = K.phone; c.drawCircle(x, y, 10f, fill)
        }
    }

    private fun steering(c: Canvas, cx: Float, cy: Float, r: Float, deg: Double?) {
        c.save(); c.rotate((deg ?: 0.0).toFloat(), cx, cy)
        stroke.color = if (deg == null) K.track else K.wheel; stroke.strokeWidth = r * 0.2f
        c.drawCircle(cx, cy, r, stroke)
        c.drawLine(cx - r, cy + 4f, cx - r * 0.3f, cy + 4f, stroke); c.drawLine(cx + r * 0.3f, cy + 4f, cx + r, cy + 4f, stroke)
        c.drawLine(cx, cy + r * 0.3f, cx, cy + r, stroke)
        if (deg != null) { stroke.color = K.mfr; c.drawLine(cx, cy - r - 2f, cx, cy - r * 0.7f, stroke) }
        c.restore()
    }

    private fun arrow(c: Canvas, x: Float, y: Float, left: Boolean, on: Boolean?) {
        val p = Path()
        if (left) { p.moveTo(x, y + 21f); p.lineTo(x + 22f, y); p.lineTo(x + 22f, y + 12f); p.lineTo(x + 50f, y + 12f); p.lineTo(x + 50f, y + 30f); p.lineTo(x + 22f, y + 30f); p.lineTo(x + 22f, y + 42f) }
        else { p.moveTo(x + 50f, y + 21f); p.lineTo(x + 28f, y); p.lineTo(x + 28f, y + 12f); p.lineTo(x, y + 12f); p.lineTo(x, y + 30f); p.lineTo(x + 28f, y + 30f); p.lineTo(x + 28f, y + 42f) }
        p.close()
        if (on == true) { fill.color = K.green; c.drawPath(p, fill) }
        stroke.color = if (on == true) K.green else K.wheel; stroke.strokeWidth = 2.5f; c.drawPath(p, stroke)
        text(c, if (on == null) "N/A" else "MFR", x + 25f, y + 62f, 12f, if (on == null) K.faint else K.mfr, bold, Paint.Align.CENTER)
    }

    private fun belt(c: Canvas, x: Float, y: Float, buckled: Boolean?, who: String) {
        val color = when (buckled) { true -> K.greenText; false -> K.red; null -> K.faint }
        stroke.color = color; stroke.strokeWidth = 3f
        c.drawCircle(x + 20f, y + 7f, 6f, stroke)
        c.drawLine(x + 8f, y + 45f, x + 8f, y + 24f, stroke); c.drawLine(x + 32f, y + 45f, x + 32f, y + 24f, stroke)
        c.drawLine(x + 8f, y + 24f, x + 32f, y + 24f, stroke); c.drawLine(x + 10f, y + 20f, x + 30f, y + 42f, stroke)
        text(c, when (buckled) { true -> "Buckled"; false -> "UNBUCKLED"; null -> "$who N/A" }, x + 20f, y + 68f, 13f, color, bold, Paint.Align.CENTER)
    }

    private fun compass(c: Canvas, cx: Float, cy: Float, r: Float, heading: Double?) {
        fill.color = K.inner; c.drawCircle(cx, cy, r, fill)
        stroke.color = K.grid; stroke.strokeWidth = 2f; c.drawCircle(cx, cy, r, stroke)
        text(c, "N", cx, cy - r + 16f, 13f, K.faint, bold, Paint.Align.CENTER)
        if (heading != null) {
            c.save(); c.rotate(heading.toFloat(), cx, cy)
            val p = Path().apply { moveTo(cx, cy - r * 0.7f); lineTo(cx + r * 0.2f, cy + r * 0.1f); lineTo(cx, cy); lineTo(cx - r * 0.2f, cy + r * 0.1f); close() }
            fill.color = K.phone; c.drawPath(p, fill); c.restore()
        }
    }

    private fun kv(c: Canvas, k: String, v: String, x: Float, y: Float) {
        text(c, k, x, y, 15f, K.muted)
        text(c, v, x, y + 28f, 21f, if (v == "N/A") K.faint else K.text, bold)
    }

    private fun drawTrail(c: Canvas, box: RectF, pts: List<Pair<Double, Double>>) {
        stroke.color = 0xFF1E2731.toInt(); stroke.strokeWidth = 14f
        var gx = box.left + 60f
        while (gx < box.right) { c.drawLine(gx, box.top, gx + 20f, box.bottom, stroke); gx += 150f }
        c.drawLine(box.left, box.top + box.height() * 0.35f, box.right, box.top + box.height() * 0.42f, stroke)
        c.drawLine(box.left, box.top + box.height() * 0.78f, box.right, box.top + box.height() * 0.72f, stroke)
        if (pts.isEmpty()) { text(c, "Waiting for GPS fix…", box.left + 16f, box.bottom - 16f, 15f, K.faint); return }
        val last = pts.last()
        val k = box.width() / 500.0
        val mLat = 111_320.0; val mLon = 111_320.0 * cos(Math.toRadians(last.first))
        val path = Path()
        pts.forEachIndexed { i, (la, lo) ->
            val x = (box.centerX() + (lo - last.second) * mLon * k).toFloat(); val y = (box.centerY() - (la - last.first) * mLat * k).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.save(); c.clipRect(box)
        stroke.color = K.obd; stroke.strokeWidth = 4f; stroke.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        c.drawPath(path, stroke); stroke.pathEffect = null
        fill.color = 0x334CC9F0; c.drawCircle(box.centerX(), box.centerY(), 18f, fill)
        fill.color = K.obd; c.drawCircle(box.centerX(), box.centerY(), 8f, fill)
        c.restore()
    }
}
