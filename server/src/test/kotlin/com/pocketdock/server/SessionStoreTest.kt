package com.pocketdock.server

import com.pocketdock.server.SessionStore.JoinError
import com.pocketdock.server.SessionStore.JoinResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionStoreTest {
    private var now = 1_000_000L

    private fun newStore() = SessionStore(
        clock = { now },
        ttlMillis = 120_000,
        maxFailures = 5,
        failureWindowMillis = 60_000,
    )

    private fun errorOf(result: JoinResult): JoinError = (result as JoinResult.Failed).error

    @Test
    fun validTokenJoins() {
        val store = newStore()
        val session = store.create()
        assertTrue(store.join(session.id, session.token, "A") is JoinResult.Ok)
    }

    @Test
    fun tokenStillWorksJustBeforeExpiry() {
        val store = newStore()
        val session = store.create()
        now += 119_000
        assertTrue(store.join(session.id, session.token, "A") is JoinResult.Ok)
    }

    @Test
    fun expiredTokenIsRejected() {
        val store = newStore()
        val session = store.create()
        now += 120_001
        assertEquals(JoinError.EXPIRED, errorOf(store.join(session.id, session.token, "A")))
    }

    @Test
    fun tokenCannotBeReusedByTheSameClient() {
        val store = newStore()
        val session = store.create()
        assertTrue(store.join(session.id, session.token, "A") is JoinResult.Ok)
        assertEquals(JoinError.ALREADY_USED, errorOf(store.join(session.id, session.token, "A")))
    }

    @Test
    fun secondScannerIsRejected() {
        val store = newStore()
        val session = store.create()
        assertTrue(store.join(session.id, session.token, "nguoi-thu-nhat") is JoinResult.Ok)
        assertEquals(
            JoinError.ALREADY_USED,
            errorOf(store.join(session.id, session.token, "nguoi-thu-hai")),
        )
    }

    @Test
    fun wrongTokenIsRejected() {
        val store = newStore()
        val session = store.create()
        assertEquals(JoinError.BAD_TOKEN, errorOf(store.join(session.id, "token-sai", "A")))
    }

    @Test
    fun unknownSessionIsRejected() {
        val store = newStore()
        assertEquals(JoinError.NOT_FOUND, errorOf(store.join("khong-co", "x", "A")))
    }

    @Test
    fun tooManyWrongAttemptsBlocksOnlyThatClient() {
        val store = newStore()
        val session = store.create()
        repeat(5) { store.join(session.id, "sai-$it", "ke-xau") }
        assertEquals(JoinError.TOO_MANY_ATTEMPTS, errorOf(store.join(session.id, session.token, "ke-xau")))
        assertTrue(store.join(session.id, session.token, "nguoi-khac") is JoinResult.Ok)

        now += 60_001
        val session2 = store.create()
        assertTrue(store.join(session2.id, session2.token, "ke-xau") is JoinResult.Ok)
    }

    @Test
    fun tokensAreRandomAndLongEnough() {
        val store = newStore()
        val sessions = List(50) { store.create() }
        assertEquals(50, sessions.map { it.token }.toSet().size)
        assertTrue(sessions.all { it.token.length >= 22 })
    }

    @Test
    fun expireIfWaitingMarksSessionExpired() {
        val store = newStore()
        val session = store.create()
        assertTrue(store.expireIfWaiting(session.id))
        assertFalse(store.expireIfWaiting(session.id))
        assertEquals(JoinError.EXPIRED, errorOf(store.join(session.id, session.token, "A")))
    }

    @Test
    fun approveWorksOnlyAfterJoin() {
        val store = newStore()
        val session = store.create()
        assertFalse(store.approve(session.id))
        store.join(session.id, session.token, "A")
        assertTrue(store.approve(session.id))
    }
}
