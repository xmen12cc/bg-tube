package com.xmen12c.bgtube

import android.net.Uri

/**
 * Extracts an 11-character YouTube video id from URLs, share texts or raw ids.
 */
object VideoId {
    private val RAW_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val URL_IN_TEXT = Regex("https?://\\S+")

    fun isValid(id: String): Boolean = RAW_ID.matches(id)

    fun extract(text: String): String? {
        val trimmed = text.trim()
        if (RAW_ID.matches(trimmed)) return trimmed

        val candidates = buildList {
            add(trimmed)
            URL_IN_TEXT.find(trimmed)?.let { add(it.value) }
        }
        for (candidate in candidates) {
            val id = fromUri(candidate)
            if (id != null) return id
        }
        return null
    }

    private fun fromUri(text: String): String? {
        val withScheme = if (text.startsWith("http", ignoreCase = true)) text else "https://$text"
        val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null

        val id: String? = when {
            host == "youtu.be" ->
                uri.path?.trimStart('/')?.substringBefore('/')

            host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") -> {
                val segments = uri.pathSegments
                when {
                    uri.path?.substringBefore('/') == "watch" -> uri.getQueryParameter("v")
                    segments.size >= 2 && segments[0] in setOf("shorts", "embed", "live", "v") -> segments[1]
                    else -> null
                }
            }

            else -> null
        }
        return id?.takeIf { RAW_ID.matches(it) }
    }
}
