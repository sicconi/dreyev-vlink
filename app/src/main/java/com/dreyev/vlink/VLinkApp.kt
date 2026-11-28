package com.dreyev.vlink

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.dreyev.vlink.bt.BleTransport
import com.dreyev.vlink.bt.DeviceScanner
import com.dreyev.vlink.bt.LinkKind
import com.dreyev.vlink.bt.SppTransport
import com.dreyev.vlink.data.AppSettings
import com.dreyev.vlink.data.ProfileRepository
import com.dreyev.vlink.data.SavedDevice
import com.dreyev.vlink.data.SettingsRepository
import com.dreyev.vlink.obd.CarIdentity
import com.dreyev.vlink.obd.ManufacturerProfile
import com.dreyev.vlink.obd.ObdConnection
import com.dreyev.vlink.obd.ObdTransport
import com.dreyev.vlink.obd.SimulatedElm327
import com.dreyev.vlink.obd.VehicleStore
import com.dreyev.vlink.sensors.MountCalibration
import com.dreyev.vlink.sensors.PhoneLocation
import com.dreyev.vlink.sensors.PhoneMotion
import com.dreyev.vlink.service.ObdService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-wide objects. The data layer lives here (not in an Activity) so it survives
 * rotation, keeps running in the foreground service, and can be shared with the
 * Android Auto car screen.
 */
class VLinkApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = VehicleStore()
    lateinit var settingsRepo: SettingsRepository; private set
    lateinit var settings: StateFlow<AppSettings>; private set
    lateinit var profiles: ProfileRepository; private set
    lateinit var scanner: DeviceScanner; private set
    lateinit var location: PhoneLocation; private set
    lateinit var motion: PhoneMotion; private set
    lateinit var trips: com.dreyev.vlink.data.TripRecorder; private set
    lateinit var finder: com.dreyev.vlink.diag.SignalFinder; private set
    private lateinit var connection: ObdConnection
    private var simulated = false

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        settings = settingsRepo.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())
        profiles = ProfileRepository(this)
        scanner = DeviceScanner(this)
        location = PhoneLocation(this, store)
        motion = PhoneMotion(this, store)
        trips = com.dreyev.vlink.data.TripRecorder(this, store, scope)
        finder = com.dreyev.vlink.diag.SignalFinder(this)
        motion.onCalibrated = { c -> scope.launch { settingsRepo.setMountCalibration(c.encode()) } }
        scope.launch {
            settings.collect { motion.calibration = MountCalibration.decode(it.mountCalibration) }
        }
        connection = ObdConnection(
            scope, store,
            profileFor = { car -> profiles.resolve(settings.value.profileId, car, simulated) },
            log = { Log.d("VLink", it) },
        )
    }

    /** Start phone sensors (call once permissions are granted). */
    fun startPhoneSensors() {
        location.start()
        motion.start()
    }

    /** A new transport for [device], or null if Bluetooth can't find it. */
    fun transportFor(device: SavedDevice): (() -> ObdTransport)? {
        val bt = scanner.device(device.address) ?: return null
        return { if (device.kind == LinkKind.BLE) BleTransport(this, bt) else SppTransport(bt) }
    }

    val isSimulated get() = simulated

    /** Profile for the car currently connected (or last seen). */
    fun currentProfile(): ManufacturerProfile {
        val s = store.state.value
        return profiles.resolve(settings.value.profileId, CarIdentity(s.vin?.value, s.isEv), simulated)
    }

    /** Hand the adapter to the signal finder: stop live polling but keep the service running. */
    fun pauseLink() = connection.stop()

    /** Give the adapter back to the dashboard after the signal finder. */
    fun resumeLink() { if (simulated) startDemo() else autoConnect() }

    fun connect(device: SavedDevice) {
        val factory = transportFor(device) ?: return
        simulated = false
        scope.launch { settingsRepo.setDevice(device) }
        connection.start(factory)
        try {
            ContextCompat.startForegroundService(this, Intent(this, ObdService::class.java))
        } catch (e: IllegalStateException) {
            // Android 12+ can refuse a foreground start from the background; the link still runs.
            Log.w("VLink", "foreground service not started", e)
        }
    }

    /** Connect to the last used adapter, if any, unless a connection is already running. */
    fun autoConnect() {
        if (isConnectionRunning) return
        val saved = settings.value.device ?: return
        if (scanner.isEnabled) connect(saved)
    }

    fun startDemo() {
        simulated = true
        connection.start({ SimulatedElm327() }, simulated = true)
    }

    fun disconnect() {
        connection.stop()
        stopService(Intent(this, ObdService::class.java))
    }

    val isConnectionRunning get() = connection.isRunning
}
