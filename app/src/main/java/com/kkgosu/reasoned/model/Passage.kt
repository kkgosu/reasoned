package com.kkgosu.reasoned.model

import com.kkgosu.reasoned.model.LessonDisplayItem.Role
import com.kkgosu.reasoned.utils.objects
import org.json.JSONArray
import java.security.MessageDigest

internal fun fingerprint(value: String) =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal data class Passage(val id: Long, val text: String) {
    val view get() = "TEACHER [$id] $text"
    val hash get() = fingerprint(view)
    val tokens get() = (view.length + 3) / 4
}

/** Canonical completed assistant text only. Never extract drafts, answers, tool questions or plan records. */
internal fun RuntimeSession.passages(): List<Passage> {
    proseIds?.let { ids ->
        return items.filter { it.role == Role.PROSE && it.id in ids && it.text.isNotBlank() }
            .map { Passage(it.id, it.text) }
    }
    // Conservative legacy recovery. New checkpoints mark the one rendered response row explicitly,
    // including responses assembled from several completed message items.
    val canonical = JSONArray(protocol).objects()
        .filter { it.optString("type") == "message" && it.optString("role") == "assistant" }
        .map { message ->
            message.getJSONArray("content").objects()
                .filter { it.optString("type") == "output_text" }
                .joinToString("") { it.getString("text") }
        }.toSet()
    return items.filter { it.role == Role.PROSE && it.text in canonical && it.text.isNotBlank() }
        .map { Passage(it.id, it.text) }
}
