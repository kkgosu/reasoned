package com.kkgosu.learnharness

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Exact-ID metadata projected from pi-ai 1.4.0 (MIT); never an account-availability list. */
internal class ModelCatalog(context: Context) {
    data class Model(val name: String, val window: Long, val efforts: List<String>)
    private val data = JSONObject(context.assets.open("openai-models.json").bufferedReader().use { it.readText() }).getJSONObject("models")
    private val cache = context.getSharedPreferences("model-catalog", Context.MODE_PRIVATE)

    /** Connection-scoped names/IDs only, never credentials. Refresh is an explicit UI action. */
    suspend fun available(connection: String, refresh: Boolean = false, fetch: suspend () -> List<Pair<String, String>>): List<Pair<String, String>> {
        if (!refresh) {
            val saved = withContext(Dispatchers.IO) {
                try { cache.getString(connection, null)?.let { raw -> JSONArray(raw).objects().map { it.getString("id") to it.getString("name") } } }
                catch (_: org.json.JSONException) { cache.edit().remove(connection).apply(); null }
            }
            if (saved != null) return saved
        }
        val models = fetch().distinctBy { it.first }
        withContext(Dispatchers.IO) {
            val rows = JSONArray(models.map { (id, name) -> JSONObject().put("id", id).put("name", name) })
            check(cache.edit().putString(connection, rows.toString()).commit())
        }
        return models
    }

    fun forget(connection: String) { cache.edit().remove(connection).apply() }
    operator fun get(id: String): Model? = data.optJSONObject(id)?.let {
        val efforts = it.getJSONArray("efforts")
        Model(it.getString("name"), it.getLong("window"), (0 until efforts.length()).map(efforts::getString))
    }
}
