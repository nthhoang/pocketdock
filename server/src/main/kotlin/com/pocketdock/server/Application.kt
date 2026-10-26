package com.pocketdock.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.time.Duration.Companion.seconds

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

private fun defaultStore(): SessionStore {
    val ttlSeconds = System.getenv("TOKEN_TTL_SECONDS")?.toLongOrNull() ?: 120L
    return SessionStore(ttlMillis = ttlSeconds * 1000)
}

private fun iceServersFromEnv(): JsonArray {
    val urls = (System.getenv("STUN_URLS") ?: "stun:stun.l.google.com:19302")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    return buildJsonArray {
        add(
            buildJsonObject {
                put("urls", buildJsonArray { urls.forEach { add(it) } })
            }
        )
    }
}

fun Application.module(
    store: SessionStore = defaultStore(),
    iceServers: JsonArray = iceServersFromEnv(),
) {
    val webDir = File(System.getenv("WEB_DIR") ?: "../web").absoluteFile
    log.info("Thư mục web: $webDir")
    log.info("Token hết hạn sau ${store.ttlMillis / 1000} giây")
    log.info("Máy chủ STUN/TURN gửi cho hai bên: $iceServers")

    install(CallLogging)
    install(DefaultHeaders) {
        header(HttpHeaders.CacheControl, "no-cache")
    }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = 65536L
    }

    routing {
        get("/health") { call.respondText("ok") }
        signaling(store, iceServers)
        staticFiles("/", webDir)
    }
}
