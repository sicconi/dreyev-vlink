package com.dreyev.vlink.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.dreyev.vlink.VLinkApp

/**
 * Android Auto entry point. Uses the navigation category so the app gets a full drawing
 * surface for gauges. That category is meant for navigation apps, so this build is meant
 * for sideloading with Android Auto developer mode, not Play Store review.
 */
class VLinkCarAppService : CarAppService() {
    // Sideloaded app: accept any Android Auto host (Play builds would allow-list host signatures).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = VLinkSession()
}

class VLinkSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val app = carContext.applicationContext as VLinkApp
        // Same data layer as the phone UI: one adapter link feeds both screens.
        app.startPhoneSensors()
        app.autoConnect()
        return DashboardScreen(carContext, app)
    }
}
