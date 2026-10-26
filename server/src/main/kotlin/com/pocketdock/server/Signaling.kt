package com.pocketdock.server

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

private val json = Json { ignoreUnknownKeys = true }

private class Room(val session: SessionStore.Session, val desktop: WebSocketSession) {
    @Volatile
    var phone: WebSocketSession? = null
}

private fun parse(text: String): JsonObject? =
    try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun msg(type: String, vararg fields: Pair<String, Any?>): String = buildJsonObject {
    put("type", type)
    for ((key, value) in fields) {
        when (value) {
            null -> put(key, JsonNull)
            is JsonElement -> put(key, value)
            is Number -> put(key, value)
            else -> put(key, value.toString())
        }
    }
}.toString()

private suspend fun WebSocketSession.sendMsg(text: String) {
    try {
        send(Frame.Text(text))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // bên kia đã rời, bỏ qua
    }
}

private suspend fun WebSocketSession.closeQuietly(reason: String) {
    try {
        close(CloseReason(CloseReason.Codes.NORMAL, reason))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // đã đóng, bỏ qua
    }
}

fun Route.signaling(store: SessionStore, iceServers: JsonArray) {
    val rooms = ConcurrentHashMap<String, Room>()

    webSocket("/ws") {
        // Khi chạy sau đường hầm Cloudflare, địa chỉ thật của người dùng nằm trong header này
        val clientKey = call.request.headers["CF-Connecting-IP"]
            ?: call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
            ?: call.request.local.remoteHost

        var myRoom: Room? = null      // kết nối này là máy tính
        var joinedRoom: Room? = null  // kết nối này là điện thoại

        suspend fun dropRoom(room: Room, reason: String) {
            rooms.remove(room.session.id)
            store.remove(room.session.id)
            room.phone?.let {
                it.sendMsg(msg("bye", "reason" to reason))
                it.closeQuietly(reason)
            }
        }

        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val m = parse(frame.readText()) ?: continue

                when (m.str("type")) {
                    "create_session" -> {
                        if (joinedRoom != null) continue
                        myRoom?.let { dropRoom(it, "new_session") }
                        val session = store.create()
                        val room = Room(session, this)
                        rooms[session.id] = room
                        myRoom = room
                        sendMsg(
                            msg(
                                "session_created",
                                "sessionId" to session.id,
                                "token" to session.token,
                                "expiresInSec" to store.ttlMillis / 1000,
                                "iceServers" to iceServers,
                            )
                        )
                        launch {
                            delay(store.ttlMillis)
                            if (store.expireIfWaiting(session.id)) sendMsg(msg("session_expired"))
                        }
                    }

                    "join" -> {
                        if (myRoom != null || joinedRoom != null) continue
                        val result = store.join(m.str("sessionId"), m.str("token"), clientKey)
                        val session = when (result) {
                            is SessionStore.JoinResult.Failed -> {
                                sendMsg(msg("join_rejected", "reason" to result.error.name.lowercase()))
                                closeQuietly("join_rejected")
                                return@webSocket
                            }
                            is SessionStore.JoinResult.Ok -> result.session
                        }
                        val room = rooms[session.id]
                        if (room == null) {
                            sendMsg(msg("join_rejected", "reason" to "not_found"))
                            closeQuietly("join_rejected")
                            return@webSocket
                        }
                        room.phone = this
                        joinedRoom = room
                        val device = (m.str("deviceInfo") ?: "Thiết bị không rõ").take(60)
                        sendMsg(msg("waiting_approval"))
                        room.desktop.sendMsg(msg("join_request", "device" to device))
                    }

                    "approve" -> {
                        val room = myRoom ?: continue
                        if (store.approve(room.session.id)) {
                            room.phone?.sendMsg(msg("joined", "iceServers" to iceServers))
                        }
                    }

                    "reject" -> {
                        val room = myRoom ?: continue
                        rooms.remove(room.session.id)
                        store.reject(room.session.id)
                        myRoom = null
                        room.phone?.let {
                            it.sendMsg(msg("rejected"))
                            it.closeQuietly("rejected")
                        }
                    }

                    // Chuyển tiếp thông tin nối. Chỉ làm khi máy tính đã bấm Cho phép,
                    // và chỉ chuyển các trường cần thiết, không chuyển nguyên văn tin nhận được.
                    "offer" -> {
                        val room = myRoom ?: continue
                        if (room.session.state != SessionStore.State.CONNECTED) continue
                        val sdp = m.str("sdp") ?: continue
                        room.phone?.sendMsg(msg("offer", "sdp" to sdp))
                    }

                    "answer" -> {
                        val room = joinedRoom ?: continue
                        if (room.session.state != SessionStore.State.CONNECTED) continue
                        val sdp = m.str("sdp") ?: continue
                        room.desktop.sendMsg(msg("answer", "sdp" to sdp))
                    }

                    "ice" -> {
                        val room = myRoom ?: joinedRoom ?: continue
                        if (room.session.state != SessionStore.State.CONNECTED) continue
                        val candidate = m.str("candidate") ?: continue
                        val out = msg(
                            "ice",
                            "candidate" to candidate,
                            "sdpMid" to m.str("sdpMid"),
                            "sdpMLineIndex" to (m["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull,
                        )
                        val target = if (myRoom != null) room.phone else room.desktop
                        target?.sendMsg(out)
                    }

                    "bye" -> {
                        myRoom?.let { dropRoom(it, "desktop_disconnect") }
                        myRoom = null
                    }

                    else -> {}
                }
            }
        } finally {
            withContext(NonCancellable) {
                myRoom?.let { if (rooms[it.session.id] === it) dropRoom(it, "desktop_left") }
                joinedRoom?.let { room ->
                    if (rooms[room.session.id] === room) {
                        rooms.remove(room.session.id)
                        store.remove(room.session.id)
                        room.desktop.sendMsg(msg("device_left"))
                    }
                }
            }
        }
    }
}
