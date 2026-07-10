package com.dopiz.gpsjoystick

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/**
 * Batch 2: a persistent GPX library. Imported .gpx files are COPIED into app-internal storage
 * (filesDir/gpx/<id>.gpx) and their metadata stored as a small JSON array in SharedPreferences
 * (no DB, no serialization lib — same spirit as [FavoritesStore]).
 *
 * Points are never cached: [get] re-parses the stored file on demand via [GpxParser], so the
 * library holds only lightweight metadata.
 */
object GpxStore {

    /** One saved GPX. [importedAt] is caller-supplied millis (System.currentTimeMillis in app code). */
    data class Entry(
        val id: String,
        val name: String,
        val originalFilename: String,
        val pointCount: Int,
        val importedAt: Long,
    )

    /** Thrown when an imported file has no coordinate points; surfaced to the user as a clear error. */
    class EmptyGpxException : Exception()

    private const val PREFS = "gpx_library"
    private const val K_ITEMS = "items"
    private const val DIR = "gpx"

    fun list(context: Context): List<Entry> {
        val raw = prefs(context).getString(K_ITEMS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    originalFilename = o.optString("filename"),
                    pointCount = o.getInt("count"),
                    importedAt = o.getLong("importedAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Copy the picked GPX into internal storage after validating it parses to >=1 point.
     * @throws EmptyGpxException when the file has no coordinate points.
     * @throws org.xmlpull.v1.XmlPullParserException / java.io.IOException on a malformed file.
     */
    fun import(context: Context, uri: Uri, importedAt: Long): Entry {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("無法讀取檔案")
        // Validate + count BEFORE writing anything, so a bad file never leaves a file behind.
        val points = GpxParser.parse(ByteArrayInputStream(bytes))
        if (points.isEmpty()) throw EmptyGpxException()

        val id = UUID.randomUUID().toString()
        gpxDir(context).mkdirs()
        file(context, id).writeBytes(bytes)

        val filename = queryDisplayName(context, uri)
        val entry = Entry(
            id = id,
            name = filename.removeSuffix(".gpx").ifBlank { "GPX" },
            originalFilename = filename,
            pointCount = points.size,
            importedAt = importedAt,
        )
        persist(context, list(context) + entry)
        return entry
    }

    /** @return the stored route parsed on demand (empty if the file is missing/unparsable). */
    fun get(context: Context, id: String): List<GeoPt> {
        val f = file(context, id)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.inputStream().use { GpxParser.parse(it) }.map { GeoPt(it.latitude, it.longitude) }
        }.getOrDefault(emptyList())
    }

    fun rename(context: Context, id: String, name: String) {
        val trimmed = name.trim().ifBlank { return }
        persist(context, list(context).map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    fun delete(context: Context, id: String) {
        file(context, id).delete()
        persist(context, list(context).filterNot { it.id == id })
    }

    /** The stored file for [id], for export (its raw bytes are written to the user-chosen Uri). */
    fun file(context: Context, id: String): File = File(gpxDir(context), "$id.gpx")

    private fun persist(context: Context, items: List<Entry>) {
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("filename", e.originalFilename)
                    .put("count", e.pointCount)
                    .put("importedAt", e.importedAt)
            )
        }
        prefs(context).edit().putString(K_ITEMS, arr.toString()).apply()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { return it }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "GPX"
    }

    private fun gpxDir(context: Context) = File(context.filesDir, DIR)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
