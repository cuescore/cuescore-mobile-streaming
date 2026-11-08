package app.brix.core

/*
 * Profile-editor logic that used to live in Compose screens and ViewModels without
 * tests (review on 03.10). Rendering is checked by eye on a phone; these are
 * ordinary data rules, and they belong here.
 */

/**
 * Whether profile video can be saved: width, height, fps, and bitrate must be
 * positive integers. An erased field used to silently retain its previous value,
 * so people left the screen thinking they had changed the resolution (23.09 audit).
 */
fun videoFieldsValid(width: String, height: String, fps: String, bitrateKbps: String): Boolean =
    listOf(width, height, fps, bitrateKbps).all { (it.trim().toIntOrNull() ?: 0) > 0 }

/**
 * Channel priorities after editing one of them.
 *
 * Missing defaults are appended BEFORE the edit: on an old profile with an empty
 * list, editing Wi-Fi produced [WIFI(8), CELLULAR, ETHERNET, WIFI(w)] — the screen
 * showed two Wi-Fi entries, while the streamer used the first, the old default, and
 * the edit was silently lost (23.09 audit). Names are compared case-insensitively.
 */
fun List<ConnectionPriority>.withPriority(name: String, enabled: Boolean, weight: Int): List<ConnectionPriority> {
    val list = toMutableList()
    defaultConnectionPriorities().forEach { d ->
        if (list.none { it.name.equals(d.name, ignoreCase = true) }) list.add(d)
    }
    val entry = ConnectionPriority(name, enabled = enabled, weight = weight)
    val idx = list.indexOfFirst { it.name.equals(name, ignoreCase = true) }
    if (idx >= 0) list[idx] = entry else list.add(entry)
    return list
}

/**
 * Servers imported via a `brix://` / `moblin://` link are appended to the existing ones.
 *
 *
 * Exactly one remains active — the stream screen broadcasts to the first enabled
 * server, while the server list shows radio buttons. Previously imported servers
 * arrived with their `enabled` value, so multiple circles lit up after import while
 * the first server in the list was used. If an existing server is active, it stays
 * active and imported ones are disabled; otherwise the first imported enabled server (or simply the first) becomes active.
 */
fun mergeImportedServers(current: List<ServerProfile>, imported: List<ServerProfile>): List<ServerProfile> {
    val activeId = if (current.any { it.enabled }) {
        null
    } else {
        (imported.firstOrNull { it.enabled } ?: imported.firstOrNull())?.id
    }
    return current + imported.map { it.copy(enabled = it.id == activeId) }
}
