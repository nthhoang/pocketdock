package com.pocketdock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TunnelUrlTest {

    @Test
    void timDiaChiTrongDongNhatKy() {
        assertEquals("https://strain-pursuant-bathrooms-bigger.trycloudflare.com",
                TunnelUrl.find("2026-11-12T10:00:00Z INF |  https://strain-pursuant-bathrooms-bigger.trycloudflare.com  |"));
    }

    @Test
    void boQuaDiaChiApiVaDongKhongCoDiaChi() {
        assertNull(TunnelUrl.find("INF Requesting new quick Tunnel on trycloudflare.com..."));
        assertNull(TunnelUrl.find("ERR Post \"https://api.trycloudflare.com/tunnel\": dial tcp: no such host"));
        assertNull(TunnelUrl.find(""));
        assertNull(TunnelUrl.find(null));
    }

    @Test
    void layDiaChiDauTienKhiCoApiTruocDo() {
        assertEquals("https://abc-def.trycloudflare.com",
                TunnelUrl.find("https://api.trycloudflare.com va https://abc-def.trycloudflare.com"));
    }
}
