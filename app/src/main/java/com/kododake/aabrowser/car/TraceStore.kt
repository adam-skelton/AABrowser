package com.kododake.aabrowser.car

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-disk store for recorded drive traces (Vela's TripStore). The page owns the format:
 * `<id>.json` is the full trace (fix rows), `<id>.meta.json` a small summary the trace
 * list shows without reading every drive. Files live in the app's private files dir, so
 * they survive restarts and go away with an uninstall.
 */
class TraceStore(context: Context) {
    private val dir = File(context.filesDir, DIR_NAME)

    fun list(): String {
        val out = JSONArray()
        val files = dir.listFiles { file -> file.name.endsWith(META_SUFFIX) } ?: return out.toString()
        for (file in files) {
            runCatching {
                val meta = JSONObject(file.readText())
                if (!meta.has("id")) meta.put("id", file.name.removeSuffix(META_SUFFIX))
                out.put(meta)
            }.onFailure { error -> Log.w(TAG, "Unreadable trace meta ${file.name}", error) }
        }
        return out.toString()
    }

    fun read(id: String): String? {
        val safe = safeId(id) ?: return null
        val file = File(dir, safe + TRACE_SUFFIX)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun save(id: String, traceJson: String, metaJson: String): Boolean {
        val safe = safeId(id) ?: return false
        return runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            // Write-then-rename so an autosave interrupted mid-drive can't truncate the file.
            val tmp = File(dir, "$safe.tmp")
            tmp.writeText(traceJson)
            val target = File(dir, safe + TRACE_SUFFIX)
            if (!tmp.renameTo(target)) {
                target.writeText(traceJson)
                tmp.delete()
            }
            File(dir, safe + META_SUFFIX).writeText(metaJson)
            true
        }.onFailure { error -> Log.w(TAG, "Trace save failed", error) }.getOrDefault(false)
    }

    fun delete(id: String): Boolean {
        val safe = safeId(id) ?: return false
        val trace = File(dir, safe + TRACE_SUFFIX).delete()
        val meta = File(dir, safe + META_SUFFIX).delete()
        return trace || meta
    }

    private fun safeId(id: String): String? {
        val trimmed = id.trim()
        if (trimmed.isEmpty() || trimmed.length > 96) return null
        if (!trimmed.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
        return trimmed
    }

    private companion object {
        const val TAG = "AABrowserTrace"
        const val DIR_NAME = "traces"
        const val TRACE_SUFFIX = ".json"
        const val META_SUFFIX = ".meta.json"
    }
}
