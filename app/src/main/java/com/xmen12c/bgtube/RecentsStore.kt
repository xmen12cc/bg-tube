package com.xmen12c.bgtube

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recently played videos, persisted as JSON in SharedPreferences (max 10).
 */
object RecentsStore {
    data class Item(val id: String, val title: String?)

    private const val PREFS = "recents"
    private const val KEY = "items"
    private const val MAX = 10

    fun all(context: Context): List<Item> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                if (id.isEmpty()) null else Item(id, obj.optString("title").ifEmpty { null })
            }
        }.getOrDefault(emptyList())
    }

    fun touch(context: Context, id: String, title: String?) {
        val existing = all(context).find { it.id == id }
        val rest = all(context).filterNot { it.id == id }
        val item = Item(id, title ?: existing?.title)
        save(context, (listOf(item) + rest).take(MAX))
    }

    fun setTitle(context: Context, id: String, title: String) {
        save(context, all(context).map { if (it.id == id) it.copy(title = title) else it })
    }

    private fun save(context: Context, items: List<Item>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("title", item.title ?: ""))
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
