package com.dreyev.vlink.obd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End-to-end: simulator → ELM327 parser → poller → VehicleState. Runs in real time (~4 s). */
class PollerTest {
    @Test fun fullStackAgainstSimulator() = runBlocking {
        val store = VehicleStore()
        val sim = SimulatedElm327(latencyMs = 2)
        val demo = ManufacturerProfile.fromJson(SimulatedElm327.DEMO_PROFILE_JSON)
        val job = launch(Dispatchers.Default) {
            ObdPoller(Elm327(sim), store, sim.name, sim.kind, true, { id -> if (demo.matchesVin(id.vin)) demo else ManufacturerProfile.NONE }).run()
        }
        withContext(Dispatchers.Default) { delay(4_000) }
        job.cancel()
        val s = store.state.value
        assertEquals(LinkState.CONNECTED, s.link.state)
        assertEquals("1HGCM82633A004352", s.vin?.value)
        assertEquals("Demo car (simulator)", s.profileName)
        assertNotNull(s.speedKmh); assertNotNull(s.rpm); assertNotNull(s.pedalPct); assertNotNull(s.engineLoadPct)
        assertEquals(Source.OBD, s.speedKmh!!.source)
        assertEquals(23.0, s.ambientC!!.value)
        assertTrue(s.odometerKm!!.value > 77591)
        assertEquals(EngineState.ON, s.engine?.value)
        assertNotNull(s.steeringDeg); assertEquals(Source.MFR, s.steeringDeg!!.source)
        assertNotNull(s.brakePressureBar); assertNotNull(s.caliperFrontKn)
        assertEquals(Source.EST, s.caliperFrontKn!!.source)
        assertEquals(30.6, s.tires[Corner.RL]!!.pressureKpa!!.value * 0.1450377, 0.05)
        assertEquals(30.0, s.tires[Corner.RL]!!.tempC!!.value)
        assertNotNull(s.tires[Corner.FL]!!.wheelSpeedKmh)
        assertEquals(true, s.beltDriver?.value)
        assertEquals(Source.MFR, s.longitudinal()?.source)
        assertTrue(s.link.pollHz > 5, "poll rate ${s.link.pollHz}")
    }

    @Test fun detectsPetrolCar() = runBlocking { assertEquals(false, identify(electric = false).isEv) }

    @Test fun detectsElectricCar() = runBlocking {
        val id = identify(electric = true)
        assertEquals(true, id.isEv)
        assertTrue(id.evidence.contains("electric"), id.evidence)
    }

    private suspend fun identify(electric: Boolean): CarIdentity {
        var seen: CarIdentity? = null
        val sim = SimulatedElm327(latencyMs = 1, electric = electric)
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            ObdPoller(Elm327(sim), VehicleStore(), sim.name, sim.kind, true, { id -> seen = id; ManufacturerProfile.NONE }).run()
        }
        withContext(Dispatchers.Default) { while (seen == null) delay(20) }
        job.cancel()
        return seen!!
    }
}
