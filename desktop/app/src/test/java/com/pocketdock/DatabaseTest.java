package com.pocketdock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseTest {

    private Database db;

    @BeforeEach
    void moCsdl() {
        db = Database.openMemory();
    }

    @AfterEach
    void dongCsdl() {
        db.close();
    }

    @Test
    void phienMoiDuocLuuVaDong() {
        long id = db.startSession("iPhone, Chrome");
        assertTrue(id > 0);
        assertEquals(1, db.countSessions());
        assertNull(db.listSessions(5).get(0).endedAt());

        db.endSession(id, "phone_left");
        Database.SessionRow row = db.listSessions(5).get(0);
        assertNotNull(row.endedAt());
        assertEquals("phone_left", row.endReason());
        assertEquals("iPhone, Chrome", row.deviceName());
    }

    @Test
    void thietBiLapLaiThiChiTangSoLan() {
        db.startSession("iPhone, Chrome");
        db.startSession("iPhone, Chrome");
        db.startSession("Android, Chrome");

        assertEquals(2, db.countDevices());
        assertEquals(3, db.countSessions());
        Database.DeviceRow iphone = db.listDevices().stream()
                .filter(d -> d.name().equals("iPhone, Chrome")).findFirst().orElseThrow();
        assertEquals(2, iphone.timesConnected());
    }

    @Test
    void dongPhienHaiLanGiuLyDoDauTien() {
        long id = db.startSession("iPhone, Chrome");
        db.endSession(id, "phone_left");
        db.endSession(id, "app_closed");
        assertEquals("phone_left", db.listSessions(1).get(0).endReason());
    }

    @Test
    void phienConMoDuocDanhDauGianDoan() {
        db.startSession("iPhone, Chrome");
        assertEquals(1, db.closeStaleSessions());
        assertEquals("interrupted", db.listSessions(1).get(0).endReason());
        assertEquals(0, db.closeStaleSessions());
    }

    @Test
    void xoaThietBiXoaCaCacPhienCuaNo() {
        db.startSession("iPhone, Chrome");
        db.startSession("iPhone, Chrome");
        db.startSession("Android, Chrome");
        long iphoneId = db.listDevices().stream()
                .filter(d -> d.name().equals("iPhone, Chrome")).findFirst().orElseThrow().id();

        db.deleteDevice(iphoneId);

        assertEquals(1, db.countDevices());
        assertEquals(1, db.countSessions());
        assertEquals("Android, Chrome", db.listDevices().get(0).name());
    }

    @Test
    void caiDatDocGhiVaGhiDe() {
        assertEquals("720", db.getSetting("webcam_quality", "720"));
        db.setSetting("webcam_quality", "1080");
        assertEquals("1080", db.getSetting("webcam_quality", "720"));
        db.setSetting("webcam_quality", "480");
        assertEquals("480", db.getSetting("webcam_quality", "720"));
    }
}
