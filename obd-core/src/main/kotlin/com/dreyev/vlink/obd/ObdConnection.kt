package com.dreyev.vlink.obd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns one adapter connection: opens the transport, runs the poller, and reconnects
 * with back-off if the link drops (e.g. ignition off, phone out of range).
 */
class ObdConnection(
    private val scope: CoroutineScope,
    private val store: VehicleStore,
    private val profileFor: (vin: String?) -> ManufacturerProfile,
    private val log: (String) -> Unit = {},
) {
    private var job: Job? = null

    val isRunning get() = job?.isActive == true

    fun start(transportFactory: () -> ObdTransport, simulated: Boolean = false) {
        stop()
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                val transport = transportFactory()
                try {
                    store.clearCarData(
                        LinkInfo(LinkState.CONNECTING, transport.name, transport.kind, simulated = simulated, message = "Connecting…")
                    )
                    transport.open()
                    attempt = 0
                    val elm = Elm327(transport, log)
                    ObdPoller(elm, store, transport.name, transport.kind, simulated, profileFor, log = log).run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("link error: ${e.message}")
                    store.updateLink { it.copy(state = LinkState.ERROR, message = e.message ?: e.javaClass.simpleName) }
                } finally {
                    try { transport.close() } catch (_: Exception) {}
                }
                attempt++
                delay(minOf(30_000L, 2_000L * attempt))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        store.clearCarData(LinkInfo(LinkState.DISCONNECTED))
    }
}
