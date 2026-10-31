package com.pocketdock.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class SessionStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
    val ttlMillis: Long = 120_000,
    private val maxFailures: Int = 5,
    private val failureWindowMillis: Long = 60_000,
) {
    enum class State { WAITING, PENDING_APPROVAL, CONNECTED, EXPIRED, REJECTED }
    enum class JoinError { NOT_FOUND, BAD_TOKEN, EXPIRED, ALREADY_USED, TOO_MANY_ATTEMPTS }

    class Session(val id: String, val token: String, val createdAt: Long) {
        @Volatile
        var state: State = State.WAITING
    }

    sealed interface JoinResult {
        data class Ok(val session: Session) : JoinResult
        data class Failed(val error: JoinError) : JoinResult
    }

    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val failures = HashMap<String, ArrayDeque<Long>>()
    private val lock = Any()

    private fun randomText(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    /** Tạo phiên mới: mã phòng 72 bit, token 128 bit. */
    fun create(): Session {
        val session = Session(randomText(9), randomText(16), clock())
        sessions[session.id] = session
        return session
    }

    fun join(sessionId: String?, token: String?, clientKey: String): JoinResult = synchronized(lock) {
        val now = clock()
        val recent = failures.getOrPut(clientKey) { ArrayDeque() }
        while (recent.isNotEmpty() && now - recent.first() > failureWindowMillis) recent.removeFirst()
        if (recent.size >= maxFailures) {
            return@synchronized JoinResult.Failed(JoinError.TOO_MANY_ATTEMPTS)
        }

        val session = sessionId?.let { sessions[it] }
        if (session == null) {
            recent.addLast(now)
            return@synchronized JoinResult.Failed(JoinError.NOT_FOUND)
        }

        val tokenOk = token != null &&
            MessageDigest.isEqual(token.toByteArray(), session.token.toByteArray())
        if (!tokenOk) {
            recent.addLast(now)
            return@synchronized JoinResult.Failed(JoinError.BAD_TOKEN)
        }

        if (session.state == State.WAITING && now - session.createdAt > ttlMillis) {
            session.state = State.EXPIRED
        }
        when (session.state) {
            State.WAITING -> {
                session.state = State.PENDING_APPROVAL
                JoinResult.Ok(session)
            }
            State.EXPIRED -> JoinResult.Failed(JoinError.EXPIRED)
            else -> JoinResult.Failed(JoinError.ALREADY_USED)
        }
    }

    fun approve(id: String): Boolean = synchronized(lock) {
        val session = sessions[id]
        if (session != null && session.state == State.PENDING_APPROVAL) {
            session.state = State.CONNECTED
            true
        } else {
            false
        }
    }

    fun expireIfWaiting(id: String): Boolean = synchronized(lock) {
        val session = sessions[id]
        if (session != null && session.state == State.WAITING) {
            session.state = State.EXPIRED
            true
        } else {
            false
        }
    }

    fun reject(id: String) {
        sessions.remove(id)?.state = State.REJECTED
    }

    fun remove(id: String) {
        sessions.remove(id)
    }
}
