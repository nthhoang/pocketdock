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
import java.io.File

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {
    val webDir = File(System.getenv("WEB_DIR") ?: "../web").absoluteFile
    log.info("Thư mục web: $webDir")

    install(CallLogging)
    install(DefaultHeaders) {
        header(HttpHeaders.CacheControl, "no-cache")
    }

    routing {
        get("/health") { call.respondText("ok") }
        staticFiles("/", webDir)
    }
}
