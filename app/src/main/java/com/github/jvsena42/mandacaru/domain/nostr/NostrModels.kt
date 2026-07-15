package com.github.jvsena42.mandacaru.domain.nostr

import org.json.JSONArray
import org.json.JSONObject

/** NIP-01 event kinds used by Joinstr coordination. */
object NostrKind {
    const val POOL_ANNOUNCEMENT = 2022
    const val ENCRYPTED_DM = 4
}

data class NostrEvent(
    val id: String,
    val pubKey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("pubkey", pubKey)
        put("created_at", createdAt)
        put("kind", kind)
        put("tags", tagsToJson(tags))
        put("content", content)
        put("sig", sig)
    }

    companion object {
        fun fromJson(json: JSONObject): NostrEvent {
            val tagsArray = json.optJSONArray("tags") ?: JSONArray()
            val tags = List(tagsArray.length()) { i ->
                val tag = tagsArray.getJSONArray(i)
                List(tag.length()) { j -> tag.getString(j) }
            }
            return NostrEvent(
                id = json.getString("id"),
                pubKey = json.getString("pubkey"),
                createdAt = json.getLong("created_at"),
                kind = json.getInt("kind"),
                tags = tags,
                content = json.getString("content"),
                sig = json.getString("sig"),
            )
        }

        fun tagsToJson(tags: List<List<String>>): JSONArray = JSONArray().apply {
            tags.forEach { tag -> put(JSONArray(tag)) }
        }
    }
}

/** A minimal NIP-01 `REQ` filter: kinds + tag filters + since. */
data class NostrFilter(
    val kinds: List<Int> = emptyList(),
    val authors: List<String> = emptyList(),
    val pTags: List<String> = emptyList(),
    val since: Long? = null,
    val limit: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (kinds.isNotEmpty()) put("kinds", JSONArray(kinds))
        if (authors.isNotEmpty()) put("authors", JSONArray(authors))
        if (pTags.isNotEmpty()) put("#p", JSONArray(pTags))
        since?.let { put("since", it) }
        limit?.let { put("limit", it) }
    }
}
