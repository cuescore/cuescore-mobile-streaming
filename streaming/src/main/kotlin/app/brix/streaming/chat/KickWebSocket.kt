package app.brix.streaming.chat

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

/** Verified live 04.09 against a real, currently-streaming channel — see
 *  [kickSubscribeChannels] for why five channel names are subscribed at once.
 *
 *  Ключ приложения Pusher Kick меняет молча: около 08.10 старый
 *  `32cbd69e4b950bf97679` стал отвечать «App key not in this cluster», и чат
 *  Kick в 0.2.0 не подключался вовсе (Moblin сменил ключ 08.10, коммит #550).
 *  Новый проверен 26.10 на живом канале: подписка на `chatrooms.<id>.v2` и
 *  приход `ChatMessageEvent`. Сменится снова — симптом тот же: Twitch и VK
 *  работают, Kick молчит. */
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
