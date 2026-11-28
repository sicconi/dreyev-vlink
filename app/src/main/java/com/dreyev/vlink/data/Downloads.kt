package com.dreyev.vlink.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Copies log files into the phone's public Downloads/dreyev-vlink folder, where any file
 * manager, the Files app and chat apps' attach dialogs can find them.
 */
object Downloads {
    const val FOLDER = "dreyev-vlink"

    /** Path the user sees, e.g. "Downloads/dreyev-vlink/vlink-watch-20261128-0423.csv". */
    fun displayPath(name: String) = "Downloads/$FOLDER/$name"

    /** Copy [file]; returns the display path, or null if the copy failed. */
    fun save(context: Context, file: File): String? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore refused the file")
            context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        } else {
            if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                error("storage permission not granted")
            }
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
            file.copyTo(File(dir, file.name), overwrite = true)
        }
        displayPath(file.name)
    } catch (e: Exception) {
        Log.w("VLink", "could not save ${file.name} to Downloads", e)
        null
    }
}
