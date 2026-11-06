package com.pocketdock.server

import com.pocketdock.server.SessionStore.JoinResult
import com.pocketdock.server.SessionStore.ResumeError
import com.pocketdock.server.SessionStore.ResumeResult
import com.pocketdock.server.SessionStore.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResumeTest {
    private var now = 5_000_000L

    private fun newStore() = SessionStore(
        clock = { now },
        ttlMillis = 120_000,
        maxFailures = 5,
        failureWindowMillis = 60_000,
        resumeWindowMillis = 90_000,
    )

    private fun connected(store: SessionStore): SessionStore.Session {
        val session = store.create()
        assertTrue(store.join(session.id, session.token, "A") is JoinResult.Ok)
        assertTrue(store.approve(session.id))
        return session
    }

    private fun errorOf(result: ResumeResult): ResumeError = (result as ResumeResult.Failed).error

    @Test
    fun approveCapMaChoCaHaiBen() {
        val store = newStore()
        val session = connected(store)
        val phone = store.resumeToken(session.id, Role.PHONE)
        val desktop = store.resumeToken(session.id, Role.DESKTOP)
        assertNotNull(phone)
        assertNotNull(desktop)
        assertNotEquals(phone, desktop)
    }

    @Test
    fun chuaDuocChoPhepThiKhongCoMa() {
        val store = newStore()
        val session = store.create()
        assertNull(store.resumeToken(session.id, Role.PHONE))
        assertEquals(ResumeError.NOT_FOUND, errorOf(store.resume(session.id, Role.PHONE, "x", "B")))
    }

    @Test
    fun nhoiLaiTrongThoiGianGiuPhongThiThanhCongVaDoiMa() {
        val store = newStore()
        val session = connected(store)
        val first = store.resumeToken(session.id, Role.PHONE)!!
        assertTrue(store.markAway(session.id, Role.PHONE))
        now += 89_000

        val result = store.resume(session.id, Role.PHONE, first, "B")
        assertTrue(result is ResumeResult.Ok)
        val fresh = (result as ResumeResult.Ok).newToken
        assertNotEquals(first, fresh)
        assertEquals(fresh, store.resumeToken(session.id, Role.PHONE))

        // Mã cũ không dùng lại được
        assertEquals(ResumeError.BAD_TOKEN, errorOf(store.resume(session.id, Role.PHONE, first, "B")))
    }

    @Test
    fun quaThoiGianGiuPhongThiBiTuChoi() {
        val store = newStore()
        val session = connected(store)
        val token = store.resumeToken(session.id, Role.PHONE)!!
        assertTrue(store.markAway(session.id, Role.PHONE))
        now += 91_000
        assertEquals(ResumeError.EXPIRED, errorOf(store.resume(session.id, Role.PHONE, token, "B")))
    }

    @Test
    fun maCuaBenNayKhongDungChoBenKia() {
        val store = newStore()
        val session = connected(store)
        val phoneToken = store.resumeToken(session.id, Role.PHONE)!!
        assertEquals(ResumeError.BAD_TOKEN, errorOf(store.resume(session.id, Role.DESKTOP, phoneToken, "B")))
    }

    @Test
    fun thuSaiQuaNhieuThiBiKhoaTamThoi() {
        val store = newStore()
        val session = connected(store)
        val token = store.resumeToken(session.id, Role.PHONE)!!
        repeat(5) {
            assertEquals(ResumeError.BAD_TOKEN, errorOf(store.resume(session.id, Role.PHONE, "sai", "B")))
        }
        assertEquals(ResumeError.TOO_MANY_ATTEMPTS, errorOf(store.resume(session.id, Role.PHONE, token, "B")))
        // Người khác (địa chỉ khác) không bị ảnh hưởng
        assertTrue(store.resume(session.id, Role.PHONE, token, "C") is ResumeResult.Ok)
    }

    @Test
    fun heThoiGianGiuPhongThiXoaPhong() {
        val store = newStore()
        val session = connected(store)
        val token = store.resumeToken(session.id, Role.DESKTOP)!!
        assertTrue(store.markAway(session.id, Role.DESKTOP))

        now += 60_000
        assertFalse(store.expireAway(session.id, Role.DESKTOP))
        now += 31_000
        assertTrue(store.expireAway(session.id, Role.DESKTOP))
        assertEquals(ResumeError.NOT_FOUND, errorOf(store.resume(session.id, Role.DESKTOP, token, "B")))
    }

    @Test
    fun khongVangMatThiKhongBiXoa() {
        val store = newStore()
        val session = connected(store)
        now += 500_000
        assertFalse(store.expireAway(session.id, Role.PHONE))
    }

    @Test
    fun nhoiLaiXongThiBoDemGio() {
        val store = newStore()
        val session = connected(store)
        val token = store.resumeToken(session.id, Role.PHONE)!!
        assertTrue(store.markAway(session.id, Role.PHONE))
        now += 30_000
        assertTrue(store.resume(session.id, Role.PHONE, token, "B") is ResumeResult.Ok)
        now += 200_000
        assertFalse(store.expireAway(session.id, Role.PHONE))
    }
}
