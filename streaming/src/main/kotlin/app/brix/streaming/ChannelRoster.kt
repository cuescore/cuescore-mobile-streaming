package app.brix.streaming

/**
 * Which networks become stream links and which remain on standby.
 *
 * Extracted from SrtlaStreamer callbacks for the same reason as
 * [SrtlaReconnectController]: the decision depended on Android's `Network`
 * and was tested only outdoors. The `srt://` bug (audit 23.09) lived here:
 * discarding the second network permanently meant leaving Wi-Fi killed
 * the stream. [N] is the network key: `android.net.Network` in the app,
 * any type in tests.
 *
 * Rules:
 * - do not create outside an active session or at weight 0 (disabled in profile);
 * - SRTLA creates all networks: the server reassembles the stream;
 * - plain SRT uses ONE link: a second socket to the same server breaks the session
 *   (field run 14.09). Extra networks stay on standby; if the active one disappears,
 *   the first standby becomes a link. Retaining standby networks is essential:
 *   BondingNetworkManager will not report the same network twice.
 */
internal class ChannelRoster<N : Any> {

    enum class Decision { SKIP, STANDBY, ADD }

    private val standby = LinkedHashMap<N, String>()

    @Synchronized
    fun onAvailable(
        network: N,
        type: String,
        weight: Int,
        sessionActive: Boolean,
        plainSrt: Boolean,
        channelCount: Int,
    ): Decision {
        if (!sessionActive || weight <= 0) return Decision.SKIP
        if (plainSrt && channelCount > 0) {
            standby[network] = type
            return Decision.STANDBY
        }
        standby.remove(network)
        return Decision.ADD
    }

    /**
     * Network disappeared. Return a standby network to start in its place,
     * only for plain SRT left without any link.
     */
    @Synchronized
    fun onLost(network: N, plainSrt: Boolean, channelsLeft: Int, sessionActive: Boolean): Pair<N, String>? {
        standby.remove(network)
        if (!plainSrt || channelsLeft > 0 || !sessionActive) return null
        val next = standby.entries.firstOrNull() ?: return null
        standby.remove(next.key)
        return next.key to next.value
    }

    @get:Synchronized
    val standbyCount: Int get() = standby.size

    @Synchronized
    fun clear() = standby.clear()
}
