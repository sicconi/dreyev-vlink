package com.dreyev.vlink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.dreyev.vlink.obd.UnitPrefs
import com.dreyev.vlink.obd.VehicleState
import com.dreyev.vlink.ui.BottomTabs
import com.dreyev.vlink.ui.C
import com.dreyev.vlink.ui.DriveLandscape
import com.dreyev.vlink.ui.DriveScreen
import com.dreyev.vlink.ui.EnginePill
import com.dreyev.vlink.ui.ScreenHeader
import com.dreyev.vlink.ui.Tab
import com.dreyev.vlink.ui.ThemeMode
import com.dreyev.vlink.ui.TiresScreen
import com.dreyev.vlink.ui.VLinkTheme
import com.dreyev.vlink.ui.VehicleScreen
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/** store1DriveEv → 01-drive-ev.png */
private fun fileName(test: String): String {
    val n = test.removePrefix("store")
    return "0" + n.first() + "-" + n.drop(1).replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase() + ".png"
}

/**
 * Writes each store screenshot straight to [dir] under a readable name. Built with a dynamic proxy
 * and reflection because java.awt isn't on the Android unit-test compile classpath.
 */
private fun storeImageWriter(dir: File): SnapshotHandler {
    val write = Class.forName("javax.imageio.ImageIO")
        .getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, File::class.java)
    fun <T> proxy(type: Class<T>, onCall: (String, Array<Any?>) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, args -> onCall(m.name, args ?: emptyArray()) })
    return proxy(SnapshotHandler::class.java) { name, args ->
        if (name != "newFrameHandler") return@proxy null
        val snapshot = args[0] as Snapshot
        proxy(SnapshotHandler.FrameHandler::class.java) { frameCall, frameArgs ->
            if (frameCall == "handle") {
                dir.mkdirs()
                write.invoke(null, frameArgs[0], "PNG", File(dir, fileName(snapshot.testName.methodName)))
            }
            null
        }
    }
}

/**
 * Google Play store screenshots in a 2:1 phone frame. Paparazzi caps output at 1000 px, so they
 * come out 500×1000 (Play accepts 320–3840 px, at most 2:1); real phone screenshots look sharper.
 * Not part of the normal snapshot check; regenerate with
 *   VLINK_STORE_SHOTS=1 ./gradlew :app:testPlayDebugUnitTest --tests '*StoreScreenshotTest'
 * and the PNGs land in docs/play-store/screenshots/.
 */
class StoreScreenshotTest {
    private val phone = DeviceConfig.PIXEL_5.copy(
        screenWidth = 1080, screenHeight = 2160, xdpi = 420, ydpi = 420, density = Density.create(420),
    )

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = phone,
        snapshotHandler = storeImageWriter(File("../docs/play-store/screenshots")),
    )

    @Before fun onlyWhenAsked() = assumeTrue(System.getenv("VLINK_STORE_SHOTS") == "1")

    private fun screen(tab: Tab, s: VehicleState, theme: ThemeMode = ThemeMode.DARK) {
        val now = System.currentTimeMillis()
        paparazzi.snapshot {
            VLinkTheme(theme) {
                Column(Modifier.fillMaxSize().background(C.bg)) {
                    ScreenHeader(if (tab == Tab.DRIVE) "VLink connected" else tab.title, "Bluetooth Classic · ELM327 · 14 Hz", C.green, {}) { EnginePill(s) }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        when (tab) {
                            Tab.DRIVE -> DriveScreen(s, UnitPrefs(), now)
                            Tab.TIRES -> TiresScreen(s, UnitPrefs(), now)
                            Tab.VEHICLE -> VehicleScreen(s, UnitPrefs(), TestStates.trail)
                        }
                    }
                    BottomTabs(Tab.entries, tab) {}
                }
            }
        }
    }

    @Test fun store1DriveEv() = screen(Tab.DRIVE, TestStates.ev(TestStates.simulated()))
    @Test fun store2Drive() = screen(Tab.DRIVE, TestStates.simulated())
    @Test fun store3Tires() = screen(Tab.TIRES, TestStates.simulated())
    @Test fun store4Vehicle() = screen(Tab.VEHICLE, TestStates.ev(TestStates.simulated()))
    @Test fun store5DriveLight() = screen(Tab.DRIVE, TestStates.simulated(), ThemeMode.LIGHT)

    @Test fun store6Landscape() {
        paparazzi.unsafeUpdateConfig(phone.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = 2160, screenHeight = 1080))
        val s = TestStates.ev(TestStates.simulated())
        paparazzi.snapshot { VLinkTheme { Box(Modifier.fillMaxSize().background(C.bg)) { DriveLandscape(s, UnitPrefs()) } } }
    }
}
