package com.pocketdock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScreenQualityTest {

    @Test
    void mac_dinh_720p() {
        ScreenQuality q = ScreenQuality.of("720p");
        assertEquals(1280, q.width());
        assertEquals(720, q.height());
        assertEquals(30, q.fps());
    }

    @Test
    void chon_1080p() {
        ScreenQuality q = ScreenQuality.of("1080p");
        assertEquals(1920, q.width());
        assertEquals(1080, q.height());
        assertEquals(30, q.fps());
    }

    @Test
    void gia_tri_la_hoac_rong_thi_dung_720p() {
        assertEquals("720p", ScreenQuality.of(null).label());
        assertEquals("720p", ScreenQuality.of("").label());
        assertEquals("720p", ScreenQuality.of("4k").label());
    }
}
