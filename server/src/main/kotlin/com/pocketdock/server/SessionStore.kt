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
    val resumeWindowMillis: Long = 90_000,
) {
    enum class State { WAITING, PENDING_APPROVAL, CONNECTED, EXPIRED, REJECTED }
    enum class JoinError { NOT_FOUND, BAD_TOKEN, EXPIRED, ALREADY_USED, TOO_MANY_ATTEMPTS }
    enum class Role { PHONE, DESKTOP }
    enum class ResumeError { NOT_FOUND, BAD_TOKEN, EXPIRED, TOO_MANY_ATTEMPTS }

    class Session(val id: String, val token: String, val createdAt: Long) {
        @Volatile
        var state: State = State.WAITING

        // Mã tiếp tục của từng bên và thời điểm hết hạn khi bên đó đang tạm vắng.
        // Chỉ được đọc và ghi bên trong SessionStore, dưới khóa lock.
        internal val resumeTokens = HashMap<Role, String>()
        internal val awayUntil = HashMap<Role, Long>()
    }

    sealed interface JoinResult {
        data class Ok(val session: Session) : JoinResult
        data class Failed(val error: JoinError) : JoinResult
    }

    sealed interface ResumeResult {
        data class Ok(val session: Session, val newToken: String) : ResumeResult
        data class Failed(val error: ResumeError) : ResumeResult
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
            session.resumeTokens[Role.PHONE] = randomText(16)
            session.resumeTokens[Role.DESKTOP] = randomText(16)
            true
        } else {
            false
        }
    }

    /** Mã tiếp tục hiện tại của một bên (128 bit). Chỉ có sau khi phiên được cho phép. */
    fun resumeToken(id: String, role: Role): String? = synchronized(lock) {
        sessions[id]?.resumeTokens?.get(role)
    }

    /** Đánh dấu một bên đang tạm vắng và bắt đầu đếm thời gian giữ phòng. */
    fun markAway(id: String, role: Role): Boolean = synchronized(lock) {
        val session = sessions[id] ?: return@synchronized false
        if (session.state != State.CONNECTED) return@synchronized false
        session.awayUntil[role] = clock() + resumeWindowMillis
        true
    }

    /**
     * Một bên nối lại phòng bằng mã tiếp tục. Mã chỉ dùng được một lần: thành công thì
     * mã cũ hết hiệu lực và trả về mã mới. Thử sai quá nhiều lần thì bị khóa tạm thời
     * (dùng chung bộ đếm với join).
     */
    fun resume(sessionId: String?, role: Role, token: String?, clientKey: String): ResumeResult =
        synchronized(lock) {
            val now = clock()
            val recent = failures.getOrPut(clientKey) { ArrayDeque() }
            while (recent.isNotEmpty() && now - recent.first() > failureWindowMillis) recent.removeFirst()
            if (recent.size >= maxFailures) {
                return@synchronized ResumeResult.Failed(ResumeError.TOO_MANY_ATTEMPTS)
            }

            val session = sessionId?.let { sessions[it] }
            if (session == null || session.state != State.CONNECTED) {
                recent.addLast(now)
                return@synchronized ResumeResult.Failed(ResumeError.NOT_FOUND)
            }

            val expected = session.resumeTokens[role]
            val tokenOk = token != null && expected != null &&
                MessageDigest.isEqual(token.toByteArray(), expected.toByteArray())
            if (!tokenOk) {
                recent.addLast(now)
                return@synchronized ResumeResult.Failed(ResumeError.BAD_TOKEN)
            }

            val until = session.awayUntil[role]
            if (until != null && now > until) {
                return@synchronized ResumeResult.Failed(ResumeError.EXPIRED)
            }

            val fresh = randomText(16)
            session.resumeTokens[role] = fresh
            session.awayUntil.remove(role)
            ResumeResult.Ok(session, fresh)
        }

    /** Hết thời gian giữ phòng mà bên đó vẫn chưa quay lại thì xóa phòng. */
    fun expireAway(id: String, role: Role): Boolean = synchronized(lock) {
        val session = sessions[id] ?: return@synchronized false
        val until = session.awayUntil[role] ?: return@synchronized false
        if (clock() >= until) {
            sessions.remove(id)
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
