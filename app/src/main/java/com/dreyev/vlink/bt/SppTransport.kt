package com.dreyev.vlink.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.dreyev.vlink.obd.ObdTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Classic (SPP / RFCOMM) link. This is how most VLink / vLinker adapters
 * talk to Android. Pair the adapter in Android Bluetooth settings first (PIN 1234 or 0000).
 */
@SuppressLint("MissingPermission")
class SppTransport(private val device: BluetoothDevice) : ObdTransport() {
    override val name: String = device.name ?: device.address
    override val kind = "Bluetooth Classic"

    private var socket: BluetoothSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reader: Job? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        val s = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (e: IOException) {
            // Some clones only answer on channel 1 without an SDP record.
            try {
                @Suppress("DiscouragedPrivateApi")
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
            } catch (e2: Exception) {
                throw IOException("Could not connect to $name: ${e.message}", e)
            }
        }
        socket = s
        val input = s.inputStream
        reader = scope.launch {
            val buf = ByteArray(1024)
            try {
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    deliver(buf.copyOf(n))
                }
            } catch (_: IOException) {
            } finally {
                markClosed()
            }
        }
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val out = socket?.outputStream ?: throw IOException("Not connected")
        out.write(bytes)
        out.flush()
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { try { socket?.close() } catch (_: IOException) {} }
        reader?.cancel()
        scope.cancel()
        markClosed()
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
