package com.dopiz.gpsjoystick

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Feature 3: favorite locations persisted in SharedPreferences as a small JSON array
 * (no DB, no serialization lib). Each entry is {lat, lng, label}.
 */
object FavoritesStore {

    data class Fav(val lat: Double, val lng: Double, val label: String)

    private const val PREFS = "favorites"
    private const val K_ITEMS = "items"

    fun list(context: Context): List<Fav> {
        val raw = prefs(context).getString(K_ITEMS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Fav(o.getDouble("lat"), o.getDouble("lng"), o.optString("label"))
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, fav: Fav) {
        val items = list(context).toMutableList()
        items.add(fav)
        persist(context, items)
    }

    fun rename(context: Context, index: Int, newLabel: String) {
        val items = list(context).toMutableList()
        if (index in items.indices) {
            items[index] = items[index].copy(label = newLabel)
            persist(context, items)
        }
    }

    fun removeAt(context: Context, index: Int) {
        val items = list(context).toMutableList()
        if (index in items.indices) {
            items.removeAt(index)
            persist(context, items)
        }
    }

    /** Serialize every favorite to a JSON array string [{label, lat, lng}, ...]. */
    fun exportJson(context: Context): String {
        val arr = JSONArray()
        list(context).forEach { f ->
            arr.put(JSONObject().put("label", f.label).put("lat", f.lat).put("lng", f.lng))
        }
        return arr.toString()
    }

    /**
     * Merge favorites parsed from [json] into the current list, skipping entries whose
     * label+lat+lng exactly match an existing one. Returns (imported, skipped).
     * Throws on malformed JSON / missing fields.
     */
    fun importMerged(context: Context, json: String): Pair<Int, Int> {
        val arr = JSONArray(json)
        val items = list(context).toMutableList()
        val existing = items.map { Triple(it.label, it.lat, it.lng) }.toMutableSet()
        var imported = 0
        var skipped = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val fav = Fav(o.getDouble("lat"), o.getDouble("lng"), o.optString("label"))
            val key = Triple(fav.label, fav.lat, fav.lng)
            if (existing.add(key)) {
                items.add(fav)
                imported++
            } else {
                skipped++
            }
        }
        if (imported > 0) persist(context, items)
        return imported to skipped
    }

    private fun persist(context: Context, items: List<Fav>) {
        val arr = JSONArray()
        items.forEach { f ->
            arr.put(JSONObject().put("lat", f.lat).put("lng", f.lng).put("label", f.label))
        }
        prefs(context).edit().putString(K_ITEMS, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
