package app.brix.streaming.chat

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

/** Verified live 04.09 against a real, currently-streaming channel — see
 *  [kickSubscribeChannels] for why five channel names are subscribed at once.
 *
 *  The Pusher app key for Kick changes without notice: around Oct 8, the old
 *  `32cbd69e4b950bf97679` started returning "App key not in this cluster," and
 *  Kick chat in 0.2.0 wouldn't connect at all (Moblin changed its key on Oct 8,
 *  commit #550). The new key was verified on Oct 26 on a live channel: subscribing
 *  to `chatrooms.<id>.v2` and receiving `ChatMessageEvent`. If it changes again,
 *  the symptom is the same: Twitch and VK work, Kick is silent. */
internal const val KICK_PUSHER_WS_URL =
    "wss://ws-us2.pusher.com/app/34bf7a0ff419a2a775b9?protocol=7&client=js&version=7.6.0&flash=false"

/** Pusher frames are one JSON object per WS text frame — unlike Twitch IRC,
 *  no `\r\n`-batching to split here. */
internal class KickWebSocket(
    url: String,
    private val listener: ChatSocketListener,
) : WebSocketClient(URI(url)), ChatSocket {

    override fun onOpen(handshakedata: ServerHandshake?) {
        listener.onOpen()
    }

    override fun onMessage(message: String) {
        listener.onLine(message)
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        listener.onClosed()
    }

    override fun onError(ex: Exception) {
        listener.onFailure(ex.message ?: ex.javaClass.simpleName)
    }
}
