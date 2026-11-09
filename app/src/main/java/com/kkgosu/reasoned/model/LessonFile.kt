package com.kkgosu.reasoned.model

import org.json.JSONArray
import org.json.JSONObject

/** Local, session-owned source snapshot. Names are display data, never filesystem paths. */
internal data class LessonFile(val id: String, val name: String, val bytes: Long, val pages: Int = 0) {
    init {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        require(name.length in 1..512 && name.none { it.isISOControl() })
        require(bytes in 1..MAX_BYTES && pages in 0..2000)
    }

    fun json() = JSONObject().put("id", id).put("name", name).put("bytes", bytes).put("pages", pages)
    override fun toString() = name

    companion object {
        const val MAX_BYTES = 25L * 1024 * 1024
        const val MAX_TOTAL = 64L * 1024 * 1024
        const val MAX_FILES = 32

        fun parse(json: JSONObject) = LessonFile(json.getString("id"), json.getString("name"), json.getLong("bytes"), json.getInt("pages"))
        fun parse(array: JSONArray?): List<LessonFile> {
            if (array == null) return emptyList()
            require(array.length() <= MAX_FILES)
            val files = (0 until array.length()).map { parse(array.getJSONObject(it)) }
            require(files.map { it.id }.toSet().size == files.size && files.sumOf { it.bytes } <= MAX_TOTAL)
            return files
        }
    }
}
