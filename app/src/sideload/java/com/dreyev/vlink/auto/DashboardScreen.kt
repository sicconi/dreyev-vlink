package com.dreyev.vlink.auto

import android.graphics.Rect
import android.util.Log
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.dreyev.vlink.BuildConfig
import com.dreyev.vlink.R
import com.dreyev.vlink.VLinkApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The car screen: a NavigationTemplate whose map surface we paint with [CarDashRenderer].
 * The action strip switches between the Drive, Tires, Vehicle and Map pages. The Map page hands
 * the surface to [CarMapPresenter] (a real Google map) and falls back to drawing the trace.
 */
class DashboardScreen(carContext: CarContext, private val app: VLinkApp) : Screen(carContext) {
    private val renderer = CarDashRenderer()
    private var page = CarPage.DRIVE
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var dpi = 160
    private val mapPresenter = CarMapPresenter(carContext)
    private var mapFailed = false
    private var tick = 0
    private var visible: Rect? = null
    private var loop: Job? = null

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            surface = container.surface; width = container.width; height = container.height; dpi = container.dpi
            mapFailed = false
            render()
        }
        override fun onVisibleAreaChanged(visibleArea: Rect) {
            visible = Rect(visibleArea)
            // The map's padding is fixed when it attaches, so re-attach for the new area.
            if (mapPresenter.isShowing) mapPresenter.release()
            render()
        }
        override fun onStableAreaChanged(stableArea: Rect) { if (visible == null) render() }
        override fun onSurfaceDestroyed(container: SurfaceContainer) { mapPresenter.release(); surface = null }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
            }
            override fun onStart(owner: LifecycleOwner) {
                // ~10 fps is plenty for gauges and keeps the phone cool.
                loop = lifecycleScope.launch {
                    while (isActive) { render(); delay(100) }
                }
            }
            override fun onStop(owner: LifecycleOwner) { loop?.cancel(); loop = null; mapPresenter.release() }
        })
    }

    @Synchronized
    private fun render() {
        val sf = surface ?: return
        if (!sf.isValid || width == 0) return
        val area = visible?.takeIf { it.width() > 0 && it.height() > 0 } ?: Rect(0, 0, width, height)
        val trailNow = app.location.trail.toList() // both run on the main thread
        if (page == CarPage.MAP && BuildConfig.HAS_MAPS_KEY && !mapFailed) {
            if (!mapPresenter.isShowing && !mapPresenter.show(sf, width, height, dpi, area)) mapFailed = true
            if (!mapFailed) {
                // The map animates itself; feeding it once a second is enough.
                if (tick++ % 10 == 0) mapPresenter.update(app.store.state.value, app.settings.value.units, trailNow)
                return
            }
        } else if (mapPresenter.isShowing) {
            mapPresenter.release()
        }
        val canvas = try { sf.lockCanvas(null) } catch (e: Exception) { Log.w("VLink", "lockCanvas", e); return }
        try {
            renderer.draw(canvas, area, page, app.store.state.value, app.settings.value.units, trailNow)
        } finally {
            sf.unlockCanvasAndPost(canvas)
        }
    }

    // The navigation action strip allows only one text button, so pages use icons; the
    // current page is tinted cyan and also named in the dashboard header.
    private fun pageAction(p: CarPage): Action {
        val res = when (p) {
            CarPage.DRIVE -> R.drawable.ic_car_drive
            CarPage.TIRES -> R.drawable.ic_car_tires
            CarPage.VEHICLE -> R.drawable.ic_car_vehicle
            CarPage.MAP -> R.drawable.ic_car_map
        }
        val icon = CarIcon.Builder(IconCompat.createWithResource(carContext, res))
            .setTint(if (p == page) CYAN else CarColor.createCustom(0xFFB4BEC8.toInt(), 0xFFB4BEC8.toInt()))
            .build()
        return Action.Builder()
            .setIcon(icon)
            .setOnClickListener { page = p; tick = 0; render(); invalidate() }
            .build()
    }

    private companion object {
        val CYAN: CarColor = CarColor.createCustom(0xFF4CC9F0.toInt(), 0xFF4CC9F0.toInt())
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder().apply { CarPage.entries.forEach { addAction(pageAction(it)) } }.build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setBackgroundColor(CarColor.createCustom(0xFF0B0E12.toInt(), 0xFF0B0E12.toInt()))
            .build()
    }
}
