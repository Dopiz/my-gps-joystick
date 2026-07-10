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

    fun removeAt(context: Context, index: Int) {
        val items = list(context).toMutableList()
        if (index in items.indices) {
            items.removeAt(index)
            persist(context, items)
        }
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
