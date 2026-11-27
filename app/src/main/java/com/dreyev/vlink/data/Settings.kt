package com.dreyev.vlink.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dreyev.vlink.bt.LinkKind
import com.dreyev.vlink.obd.TempUnit
import com.dreyev.vlink.obd.UnitPrefs
import com.dreyev.vlink.obd.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "vlink_settings")

data class SavedDevice(val address: String, val name: String, val kind: LinkKind)

data class AppSettings(
    val units: UnitPrefs = UnitPrefs(),
    val device: SavedDevice? = null,
    /** "auto" = pick by VIN, else a profile id. */
    val profileId: String = "auto",
    val keepScreenOn: Boolean = true,
    val mountCalibration: String? = null,
)

/** Persisted settings (DataStore). First launch = Imperial + °F. */
class SettingsRepository(private val context: Context) {
    private object K {
        val system = stringPreferencesKey("units_system")
        val temp = stringPreferencesKey("units_temp")
        val devAddr = stringPreferencesKey("device_address")
        val devName = stringPreferencesKey("device_name")
        val devKind = stringPreferencesKey("device_kind")
        val profile = stringPreferencesKey("profile_id")
        val screenOn = booleanPreferencesKey("keep_screen_on")
        val mount = stringPreferencesKey("mount_calibration")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings(): AppSettings {
        val sys = this[K.system]?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() } ?: UnitSystem.IMPERIAL
        val temp = this[K.temp]?.let { runCatching { TempUnit.valueOf(it) }.getOrNull() } ?: TempUnit.F
        val addr = this[K.devAddr]
        return AppSettings(
            units = UnitPrefs(sys, temp),
            device = addr?.let {
                SavedDevice(it, this[K.devName] ?: it, runCatching { LinkKind.valueOf(this[K.devKind] ?: "") }.getOrDefault(LinkKind.CLASSIC))
            },
            profileId = this[K.profile] ?: "auto",
            keepScreenOn = this[K.screenOn] ?: true,
            mountCalibration = this[K.mount],
        )
    }

    suspend fun setUnitSystem(s: UnitSystem) = context.dataStore.edit { it[K.system] = s.name }
    suspend fun setTempUnit(t: TempUnit) = context.dataStore.edit { it[K.temp] = t.name }
    suspend fun resetUnits() = context.dataStore.edit {
        it[K.system] = UnitSystem.IMPERIAL.name; it[K.temp] = TempUnit.F.name
    }
    suspend fun setDevice(d: SavedDevice?) = context.dataStore.edit {
        if (d == null) { it.remove(K.devAddr); it.remove(K.devName); it.remove(K.devKind) }
        else { it[K.devAddr] = d.address; it[K.devName] = d.name; it[K.devKind] = d.kind.name }
    }
    suspend fun setProfile(id: String) = context.dataStore.edit { it[K.profile] = id }
    suspend fun setKeepScreenOn(v: Boolean) = context.dataStore.edit { it[K.screenOn] = v }
    suspend fun setMountCalibration(v: String?) = context.dataStore.edit {
        if (v == null) it.remove(K.mount) else it[K.mount] = v
    }
}
