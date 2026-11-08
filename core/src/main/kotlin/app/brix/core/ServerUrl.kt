package app.brix.core

/**
 * Whether this resembles a server URL: scheme, `://` and a nonempty host.
 *
 * Parse manually, not with `java.net.URI`: it returns `host = null` for
 * underscores (`belabox_home.local`) and throws on `|` or spaces in streamid,
 * rejecting URLs that actually connect successfully. The goal here is narrower:
 * catch empty fields and URLs without schemes, not validate against the RFC.
 */
fun looksLikeServerUrl(raw: String): Boolean {
    val s = raw.trim()
    val sep = s.indexOf("://")
    if (sep <= 0) return false
    val scheme = s.substring(0, sep)
    if (!scheme.first().isLetter() || !scheme.all { it.isLetterOrDigit() || it in "+-." }) return false
    val rest = s.substring(sep + 3)
    val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
    val host = if (authority.startsWith("[")) {
        authority.substringBefore(']').drop(1)
    } else {
        authority.substringBefore(':')
    }
    return host.isNotBlank()
}
