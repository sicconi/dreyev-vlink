package com.dreyev.vlink.data

import android.content.Context
import android.net.Uri
import com.dreyev.vlink.obd.ManufacturerProfile
import com.dreyev.vlink.obd.SimulatedElm327
import java.io.File

/**
 * Manufacturer profiles: built-in ones from the assets/profiles folder plus any the user
 * imports (stored in app files). Profiles are plain JSON so new cars need no rebuild.
 */
class ProfileRepository(private val context: Context) {
    private val userDir = File(context.filesDir, "profiles").apply { mkdirs() }

    val demo: ManufacturerProfile = ManufacturerProfile.fromJson(SimulatedElm327.DEMO_PROFILE_JSON)

    fun all(): List<ManufacturerProfile> {
        val builtIn = context.assets.list("profiles").orEmpty().filter { it.endsWith(".json") }.mapNotNull { f ->
            runCatching { ManufacturerProfile.fromJson(context.assets.open("profiles/$f").bufferedReader().readText()) }.getOrNull()
        }
        val user = userDir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull {
            runCatching { ManufacturerProfile.fromJson(it.readText()) }.getOrNull()
        }
        return listOf(ManufacturerProfile.NONE) + builtIn + user
    }

    /** Validates and stores a profile picked with the system file picker. */
    fun import(uri: Uri): Result<ManufacturerProfile> = runCatching {
        val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        val p = ManufacturerProfile.fromJson(text)
        File(userDir, "${p.id}.json").writeText(text)
        p
    }

    fun resolve(selectedId: String, vin: String?, simulated: Boolean): ManufacturerProfile {
        if (simulated) return demo
        val list = all()
        if (selectedId != "auto") return list.firstOrNull { it.id == selectedId } ?: ManufacturerProfile.NONE
        return list.firstOrNull { it.id != "none" && it.matchesVin(vin) } ?: ManufacturerProfile.NONE
    }
}
