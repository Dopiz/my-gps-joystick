package com.dopiz.gpsjoystick

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Feature 3: favorite locations persisted in SharedPreferences as a small JSON array
 * (no DB, no serialization lib). Each entry is {lat, lng, label, category}.
 * Categories live in the same prefs under a separate JSON array of names; "未分類"
 * is implicit, always first, and can never be renamed or deleted.
 */
object FavoritesStore {

    const val UNCATEGORIZED = "未分類"

    data class Fav(
        val lat: Double,
        val lng: Double,
        val label: String,
        val category: String = UNCATEGORIZED,
    )

    private const val PREFS = "favorites"
    private const val K_ITEMS = "items"
    private const val K_CATEGORIES = "categories"

    fun list(context: Context): List<Fav> {
        val raw = prefs(context).getString(K_ITEMS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> parse(arr.getJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, fav: Fav) {
        val items = list(context).toMutableList()
        items.add(fav)
        persist(context, items)
        if (fav.category != UNCATEGORIZED) addCategory(context, fav.category)
    }

    fun rename(context: Context, index: Int, newLabel: String) {
        updateAt(context, index) { it.copy(label = newLabel) }
    }

    /** Replace the favorite at [index] wholesale (label / coordinate / category edits). */
    fun updateAt(context: Context, index: Int, fav: Fav) = updateAt(context, index) { fav }

    private fun updateAt(context: Context, index: Int, transform: (Fav) -> Fav) {
        val items = list(context).toMutableList()
        if (index in items.indices) {
            items[index] = transform(items[index])
            persist(context, items)
        }
    }

    /** Move every index in [indices] into [category]. Returns how many rows changed. */
    fun setCategory(context: Context, indices: Collection<Int>, category: String): Int {
        val items = list(context).toMutableList()
        var moved = 0
        indices.forEach { i ->
            if (i in items.indices && items[i].category != category) {
                items[i] = items[i].copy(category = category)
                moved++
            }
        }
        if (moved > 0) persist(context, items)
        return moved
    }

    fun removeAt(context: Context, index: Int) {
        val items = list(context).toMutableList()
        if (index in items.indices) {
            items.removeAt(index)
            persist(context, items)
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(K_ITEMS).apply()
    }

    // ---- categories ----------------------------------------------------

    /** "未分類" first, then the user-defined names in insertion order. */
    fun listCategories(context: Context): List<String> =
        listOf(UNCATEGORIZED) + customCategories(context)

    /** Returns false when [name] is blank or already exists (case-sensitive). */
    fun addCategory(context: Context, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == UNCATEGORIZED) return false
        val cats = customCategories(context).toMutableList()
        if (cats.contains(trimmed)) return false
        cats.add(trimmed)
        persistCategories(context, cats)
        return true
    }

    /** Rename a custom category and re-point every favorite that used it. */
    fun renameCategory(context: Context, old: String, new: String): Boolean {
        val trimmed = new.trim()
        if (old == UNCATEGORIZED || trimmed.isEmpty() || trimmed == UNCATEGORIZED) return false
        val cats = customCategories(context).toMutableList()
        val at = cats.indexOf(old)
        if (at < 0 || cats.contains(trimmed)) return false
        cats[at] = trimmed
        persistCategories(context, cats)
        persist(context, list(context).map { if (it.category == old) it.copy(category = trimmed) else it })
        return true
    }

    /** Drop a custom category; its favorites fall back to "未分類". */
    fun deleteCategory(context: Context, name: String) {
        if (name == UNCATEGORIZED) return
        val cats = customCategories(context).toMutableList()
        if (!cats.remove(name)) return
        persistCategories(context, cats)
        persist(context, list(context).map {
            if (it.category == name) it.copy(category = UNCATEGORIZED) else it
        })
    }

    private fun customCategories(context: Context): List<String> {
        val raw = prefs(context).getString(K_CATEGORIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
                .filter { it.isNotBlank() && it != UNCATEGORIZED }
                .distinct()
        }.getOrDefault(emptyList())
    }

    private fun persistCategories(context: Context, cats: List<String>) {
        val arr = JSONArray()
        cats.forEach { arr.put(it) }
        prefs(context).edit().putString(K_CATEGORIES, arr.toString()).apply()
    }

    // ---- import / export -----------------------------------------------

    /** Serialize every favorite to a JSON array string [{label, lat, lng, category}, ...]. */
    fun exportJson(context: Context): String {
        val arr = JSONArray()
        list(context).forEach { f ->
            arr.put(
                JSONObject()
                    .put("label", f.label)
                    .put("lat", f.lat)
                    .put("lng", f.lng)
                    .put("category", f.category)
            )
        }
        return arr.toString()
    }

    /**
     * Merge favorites parsed from [json] into the current list, skipping entries whose
     * label+lat+lng exactly match an existing one (category is not part of the key).
     * Unknown category names are created on the fly. Returns (imported, skipped).
     * Throws on malformed JSON / missing fields.
     */
    fun importMerged(context: Context, json: String): Pair<Int, Int> {
        val arr = JSONArray(json)
        val items = list(context).toMutableList()
        val existing = items.map { Triple(it.label, it.lat, it.lng) }.toMutableSet()
        val newCategories = LinkedHashSet<String>()
        var imported = 0
        var skipped = 0
        for (i in 0 until arr.length()) {
            val fav = parse(arr.getJSONObject(i))
            val key = Triple(fav.label, fav.lat, fav.lng)
            if (existing.add(key)) {
                items.add(fav)
                newCategories.add(fav.category)
                imported++
            } else {
                skipped++
            }
        }
        if (imported > 0) {
            persist(context, items)
            newCategories.forEach { addCategory(context, it) }
        }
        return imported to skipped
    }

    /** Legacy rows have no "category" key — they read back as 未分類. */
    private fun parse(o: JSONObject): Fav {
        val category = o.optString("category").trim().ifEmpty { UNCATEGORIZED }
        return Fav(o.getDouble("lat"), o.getDouble("lng"), o.optString("label"), category)
    }

    private fun persist(context: Context, items: List<Fav>) {
        val arr = JSONArray()
        items.forEach { f ->
            arr.put(
                JSONObject()
                    .put("lat", f.lat)
                    .put("lng", f.lng)
                    .put("label", f.label)
                    .put("category", f.category)
            )
        }
        prefs(context).edit().putString(K_ITEMS, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
