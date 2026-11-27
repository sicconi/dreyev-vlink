package com.dreyev.vlink.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.dreyev.vlink.obd.ObdTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Low Energy link for VLink models that use BLE (e.g. vLinker MC+ / "IOS-Vlink").
 * Finds a service with one notify characteristic (adapter → phone) and one write
 * characteristic (phone → adapter), which is how ELM327 BLE bridges work.
 */
@SuppressLint("MissingPermission")
class BleTransport(private val context: Context, private val device: BluetoothDevice) : ObdTransport() {
    override val name: String = device.name ?: device.address
    override val kind = "Bluetooth LE"

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var connected = CompletableDeferred<Unit>()
    private var discovered = CompletableDeferred<Unit>()
    private var descriptorDone = CompletableDeferred<Unit>()
    private var writeDone: CompletableDeferred<Unit>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val e = IOException("BLE disconnected (status $status)")
                connected.completeExceptionally(e); discovered.completeExceptionally(e)
                writeDone?.completeExceptionally(e)
                markClosed()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            discovered.complete(Unit)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorDone.complete(Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone?.complete(Unit)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { deliver(it.copyOf()) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(value.copyOf())
        }
    }

    override suspend fun open() {
        connected = CompletableDeferred(); discovered = CompletableDeferred(); descriptorDone = CompletableDeferred()
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Could not open GATT")
        gatt = g
        withTimeout(15_000) { connected.await(); discovered.await() }

        val services = g.services.sortedByDescending { it.uuid.toString().lowercase().substring(4, 8) in PREFERRED }
        var notify: BluetoothGattCharacteristic? = null
        for (s in services) {
            val n = s.characteristics.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }
            val w = s.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            if (n != null && w != null) { notify = n; writeChar = w; break }
        }
        val n = notify ?: throw IOException("No ELM327 serial service found on $name")
        g.setCharacteristicNotification(n, true)
        n.getDescriptor(CCCD)?.let { d ->
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
            withTimeout(5_000) { descriptorDone.await() }
        }
        g.requestMtu(185)
        delay(300)
    }

    override suspend fun write(bytes: ByteArray) {
        val g = gatt ?: throw IOException("Not connected")
        val c = writeChar ?: throw IOException("Not connected")
        val withResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        for (chunk in bytes.toList().chunked(20)) {
            val data = chunk.toByteArray()
            val done = CompletableDeferred<Unit>().also { writeDone = it }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, type)
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (withResponse) withTimeout(2_000) { done.await() } else delay(8)
        }
    }

    override suspend fun close() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        markClosed()
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Short UUIDs commonly used by ELM327 BLE bridges. */
        val PREFERRED = setOf("fff0", "ffe0", "18f0", "ffc0", "ae30")
    }
}
