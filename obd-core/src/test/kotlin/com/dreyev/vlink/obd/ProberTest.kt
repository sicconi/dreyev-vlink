package com.dreyev.vlink.obd

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProberTest {
    private fun prober(rows: MutableList<Prober.ProbeRow>): Prober {
        val elm = Elm327(SimulatedElm327(latencyMs = 0))
        runBlocking { elm.initialize() }
        return Prober(elm, { rows += it })
    }

    @Test fun checksEveryProfileRequest() = runBlocking {
        val rows = ArrayList<Prober.ProbeRow>()
        val demo = ManufacturerProfile.fromJson(SimulatedElm327.DEMO_PROFILE_JSON)
        val result = prober(rows).checkProfile(demo)
        assertEquals(demo.pids.map { (it.header ?: "7DF") to it.request }.distinct().size, result.size)
        assertTrue(result.all { it.ok }, result.filterNot { it.ok }.joinToString { it.request + " " + it.status })
        assertTrue(result.first { it.request.contains("F001") }.note.startsWith("brakePressure="))
    }

    @Test fun scanSkipsMissingEcusAndKeepsPositiveReplies() = runBlocking {
        val rows = ArrayList<Prober.ProbeRow>()
        val found = prober(rows).scan(listOf("7B0", "7A0"), { listOf("F001", "F002", "F0FF") })
        assertEquals(listOf("22F001", "22F002"), found.map { it.request })
        assertTrue(rows.none { it.header == "7A0" && it.phase == "scan" })              // absent ECU not scanned
        assertTrue(rows.any { it.request == "22F0FF" && it.status.startsWith("NEG 31") })
    }

    @Test fun csvQuotesRawText() {
        val r = Prober.ProbeRow(1, "scan", "7B0", "22F001", "OK", "0102", "0:62F001|1:0102", 12, "left signal on")
        assertEquals("1,scan,7B0,22F001,OK,0102,\"0:62F001|1:0102\",12,left signal on,", r.csv())
    }
}
