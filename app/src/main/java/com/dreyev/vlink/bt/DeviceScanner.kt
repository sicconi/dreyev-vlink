package com.dreyev.vlink.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LinkKind { CLASSIC, BLE }

data class AdapterDevice(
    val name: String,
    val address: String,
    val kind: LinkKind,
    val bonded: Boolean,
    val looksLikeObd: Boolean,
)

/** Lists paired Bluetooth devices and scans for BLE adapters. */
@SuppressLint("MissingPermission")
class DeviceScanner(private val context: Context) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _devices = MutableStateFlow<List<AdapterDevice>>(emptyList())
    val devices: StateFlow<List<AdapterDevice>> = _devices.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    val isAvailable get() = adapter != null
    val isEnabled get() = adapter?.isEnabled == true

    fun device(address: String): BluetoothDevice? = adapter?.getRemoteDevice(address)

    fun refreshBonded() {
        val bonded = try { adapter?.bondedDevices.orEmpty() } catch (_: SecurityException) { emptySet() }
        val list = bonded.map {
            val n = it.name ?: it.address
            AdapterDevice(n, it.address, if (it.type == BluetoothDevice.DEVICE_TYPE_LE) LinkKind.BLE else LinkKind.CLASSIC, true, looksLikeObd(n))
        }
        _devices.update { old -> merge(old.filter { !it.bonded }, list) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val n = result.device.name ?: result.scanRecord?.deviceName ?: return
            val d = AdapterDevice(n, result.device.address, LinkKind.BLE, false, looksLikeObd(n))
            _devices.update { merge(it, listOf(d)) }
        }
    }

    fun startBleScan() {
        val s = adapter?.bluetoothLeScanner ?: return
        try {
            s.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            _scanning.value = true
        } catch (_: SecurityException) {}
    }

    fun stopBleScan() {
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        _scanning.value = false
    }

    private fun merge(a: List<AdapterDevice>, b: List<AdapterDevice>): List<AdapterDevice> =
        (a + b).associateBy { it.address + it.kind }.values
            .sortedWith(compareByDescending<AdapterDevice> { it.looksLikeObd }.thenByDescending { it.bonded }.thenBy { it.name })

    companion object {
        private val hints = listOf("vlink", "v-link", "vlinker", "obd", "elm", "vgate", "obdii", "obd2")
        fun looksLikeObd(name: String) = hints.any { name.lowercase().contains(it) }
    }
}
