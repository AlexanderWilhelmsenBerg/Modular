package io.github.alexanderwilhelmsenberg.modular.webui

import java.net.URI

data class PageAddress(val uri: URI) {
    val origin: String get() = "${uri.scheme}://${uri.rawAuthority}"

    fun sameOrigin(other: URI): Boolean =
        uri.scheme.equals(other.scheme, true) && uri.host.equals(other.host, true) &&
            effectivePort(uri) == effectivePort(other) && other.rawUserInfo == null

    companion object {
        fun parse(input: String, allowLocalHttp: Boolean): PageAddress? {
            val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (uri.rawUserInfo != null || uri.port !in -1..65535 || uri.port == 0) return null
            if (uri.scheme != "https" && !(allowLocalHttp && uri.scheme == "http" && localHost(host))) return null
            return PageAddress(uri)
        }

        private fun localHost(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local") || host == "[::1]") return true
            if (host.startsWith("[") && host.endsWith("]")) {
                val prefix = host.substring(1).substringBefore(':').toIntOrNull(16) ?: return false
                return prefix in 0xfc00..0xfdff || prefix in 0xfe80..0xfebf
            }
            val parts = host.split('.')
            // WebView interprets zero-prefixed IPv4 components as octal.
            if (parts.any { it.length > 1 && it.startsWith('0') }) return false
            val bytes = parts.map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } }
            if (bytes.size != 4 || bytes.any { it == null }) return false
            return bytes[0] == 10 || bytes[0] == 127 || (bytes[0] == 192 && bytes[1] == 168) ||
                (bytes[0] == 172 && bytes[1] in 16..31)
        }

        private fun effectivePort(uri: URI): Int =
            if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
    }
}
