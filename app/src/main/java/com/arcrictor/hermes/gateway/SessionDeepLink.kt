package com.arcrictor.hermes.gateway

import java.net.URI

/** Parses the R7 link `hermes://session/<stored-id>`; stored ids remain opaque. */
object SessionDeepLink {
    fun storedId(raw: String?): String? = runCatching {
        val uri = URI(raw ?: return null)
        if (uri.scheme != "hermes" || uri.host != "session") return null
        if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
        val path = uri.path ?: return null
        path.removePrefix("/").takeIf { it.isNotBlank() && '/' !in it }
    }.getOrNull()
}
