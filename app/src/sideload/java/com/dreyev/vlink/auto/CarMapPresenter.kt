package com.dreyev.vlink.auto

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import com.dreyev.vlink.obd.UnitPrefs
import com.dreyev.vlink.obd.VehicleState
import com.dreyev.vlink.ui.DARK_MAP_STYLE
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import java.util.Locale

/**
 * Shows a Google map with the vehicle's trace on the Android Auto surface.
 *
 * The Maps SDK can only draw into a View, and a car app only gets a Surface, so the map is put
 * in a [Presentation] on a private [VirtualDisplay] whose output is the car surface. While this
 * is attached the surface belongs to the virtual display; [release] hands it back so the gauge
 * pages can draw on it again.
 */
class CarMapPresenter(private val context: Context) {
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: GoogleMap? = null
    private var trace: Polyline? = null
    private var dot: Circle? = null
    private var hud: HudView? = null
    private var lastCount = -1

    val isShowing get() = presentation?.isShowing == true

    /** Attach to [surface]. Returns false if the platform refused (the caller then falls back to drawing). */
    fun show(surface: Surface, width: Int, height: Int, dpi: Int, visible: Rect?): Boolean {
        if (isShowing) return true
        return try {
            MapsInitializer.initialize(context)
            val dm = context.getSystemService(DisplayManager::class.java)
            val vd = dm.createVirtualDisplay(
                "vlink-car-map", width, height, dpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            display = vd
            val p = object : Presentation(context, vd.display) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    val root = FrameLayout(getContext())
                    val mv = MapView(getContext(), GoogleMapOptions().compassEnabled(false).mapToolbarEnabled(false).zoomControlsEnabled(false))
                    mv.onCreate(null); mv.onStart(); mv.onResume()
                    mv.getMapAsync { gm ->
                        map = gm
                        gm.setMapStyle(MapStyleOptions(DARK_MAP_STYLE))
                        gm.uiSettings.setAllGesturesEnabled(false)
                        visible?.let { gm.setPadding(it.left, it.top, width - it.right, height - it.bottom) }
                    }
                    mapView = mv
                    root.addView(mv, FrameLayout.LayoutParams(-1, -1))
                    val h = HudView(getContext(), visible)
                    hud = h
                    root.addView(h, FrameLayout.LayoutParams(-1, -1))
                    setContentView(root)
                }
            }
            p.show()
            presentation = p
            lastCount = -1
            true
        } catch (e: Exception) {
            Log.w("VLink", "car map not available", e)
            release()
            false
        }
    }

    /** Push the latest position and trace (call ~1×/s). */
    fun update(s: VehicleState, u: UnitPrefs, trail: List<Pair<Double, Double>>) {
        hud?.let { it.state = s; it.units = u; it.invalidate() }
        val gm = map ?: return
        val last = trail.lastOrNull()?.let { LatLng(it.first, it.second) } ?: return
        if (trail.size != lastCount) {
            lastCount = trail.size
            val pts = trail.map { LatLng(it.first, it.second) }
            if (trace == null) trace = gm.addPolyline(PolylineOptions().addAll(pts).color(0xFF4CC9F0.toInt()).width(12f))
            else trace?.points = pts
        }
        if (dot == null) dot = gm.addCircle(CircleOptions().center(last).radius(6.0).fillColor(0xFF4CC9F0.toInt()).strokeColor(0xFF141A21.toInt()).strokeWidth(5f))
        else dot?.center = last
        val moving = (s.gps?.value?.speedKmh ?: 0.0) > 5.0
        val bearing = if (moving) (s.gps?.value?.headingDeg ?: 0.0).toFloat() else gm.cameraPosition.bearing
        gm.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(last).zoom(16.5f).bearing(bearing).tilt(30f).build()), 800, null)
    }

    fun release() {
        try { mapView?.onPause(); mapView?.onStop(); mapView?.onDestroy() } catch (_: Exception) {}
        try { presentation?.dismiss() } catch (_: Exception) {}
        display?.release()
        presentation = null; display = null; mapView = null; map = null; trace = null; dot = null; hud = null
    }

    /** Speed / power chips over the map, inside the visible area. */
    private class HudView(context: Context, private val visible: Rect?) : View(context) {
        var state: VehicleState? = null
        var units: UnitPrefs = UnitPrefs()
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE141A21.toInt() }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA6B2.toInt(); textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f; typeface = Typeface.DEFAULT_BOLD }

        override fun onDraw(c: Canvas) {
            val s = state ?: return
            val x0 = (visible?.left ?: 0) + 24f
            val y0 = (visible?.top ?: 0) + 24f
            val speed = (s.speedKmh?.value ?: s.gps?.value?.speedKmh)?.let { units.fmtSpeed(it) } ?: "N/A"
            chip(c, x0, y0, units.speedUnit.uppercase(), speed, Color.WHITE)
            if (s.isEv) {
                val kw = s.hvPowerKw?.value
                val col = when { kw == null -> 0xFF8C99A6.toInt(); kw < -0.5 -> 0xFF3DDC97.toInt(); else -> 0xFF4CC9F0.toInt() }
                chip(c, x0 + 190f, y0, "kW", kw?.let { String.format(Locale.US, "%.0f", it) } ?: "N/A", col)
            }
        }

        private fun chip(c: Canvas, x: Float, y: Float, l: String, v: String, color: Int) {
            c.drawRoundRect(RectF(x, y, x + 170f, y + 96f), 18f, 18f, bg)
            c.drawText(l, x + 16f, y + 30f, label)
            value.color = color
            c.drawText(v, x + 16f, y + 78f, value)
        }
    }
}
