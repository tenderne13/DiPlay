package com.shilapi.xcertplay

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

/** Saves an app-owned report without depending on an OEM's document-picker activity. */
internal object DiagnosticExportStore {
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(resolver: ContentResolver, fileName: String, report: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DiPlay")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads could not create the report")
        try {
            write(resolver, uri, report)
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Downloads could not publish the report")
            return uri
        } catch (error: Exception) {
            // Only remove the entry created by this call; never leave a partial report behind.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    fun write(resolver: ContentResolver, uri: Uri, report: String) {
        val stream = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Report destination is unavailable")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
    }

    /**
     * Pre-Android 10 path for head units without a document picker: the app-specific
     * external directory needs no permission, but some OEM file managers cannot reach it.
     */
    fun saveToAppStorage(context: Context, fileName: String, report: String): File {
        val base = context.getExternalFilesDir(null)
            ?: throw IOException("External app storage is unavailable")
        return saveToDirectory(base, fileName, report)
    }

    /**
     * Pre-Android 10 path reachable by any file manager. Requires WRITE_EXTERNAL_STORAGE,
     * which the app only requests on API 28 and below.
     */
    @Suppress("DEPRECATION")
    fun saveToPublicDownloads(fileName: String, report: String): File =
        saveToDirectory(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName, report)

    private fun saveToDirectory(base: File, fileName: String, report: String): File {
        val dir = File(base, "DiPlay")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.absolutePath}")
        val target = File(dir, fileName)
        try {
            target.writeText(report, Charsets.UTF_8)
        } catch (error: Exception) {
            runCatching { target.delete() }
            throw error
        }
        return target
    }
}
