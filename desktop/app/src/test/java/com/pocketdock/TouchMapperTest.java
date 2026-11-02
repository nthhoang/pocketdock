package com.pocketdock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import org.junit.jupiter.api.Test;

class TouchMapperTest {

    // Màn hình 1920x1080 phóng 125% cho vùng logic 1536x864 (số đo ở W1)
    private static final TouchMapper.Bounds HD125 = new TouchMapper.Bounds(0, 0, 1536, 864);

    @Test
    void gocTrenTrai() {
        assertEquals(new Point(0, 0), TouchMapper.map(0, 0, HD125).orElseThrow());
    }

    @Test
    void gocDuoiPhaiLaDiemCuoi() {
        assertEquals(new Point(1535, 863), TouchMapper.map(1, 1, HD125).orElseThrow());
    }

    @Test
    void giuaManHinh() {
        assertEquals(new Point(768, 432), TouchMapper.map(0.5, 0.5, HD125).orElseThrow());
    }

    @Test
    void ngoaiMepThiKepVaoMep() {
        assertEquals(new Point(1535, 0), TouchMapper.map(2, -1, HD125).orElseThrow());
    }

    @Test
    void khongPhaiSoThiBoQua() {
        assertTrue(TouchMapper.map(Double.NaN, 0, HD125).isEmpty());
        assertTrue(TouchMapper.map(0, Double.POSITIVE_INFINITY, HD125).isEmpty());
    }

    @Test
    void goctoaDoAmKhiCoManHinhBenTrai() {
        TouchMapper.Bounds left = new TouchMapper.Bounds(-1920, 0, 1920, 1080);
        assertEquals(new Point(-1920, 0), TouchMapper.map(0, 0, left).orElseThrow());
        assertEquals(new Point(-1, 1079), TouchMapper.map(1, 1, left).orElseThrow());
    }

    @Test
    void kichThuocKhongHopLeThiBaoLoi() {
        assertThrows(IllegalArgumentException.class, () -> new TouchMapper.Bounds(0, 0, 0, 1080));
    }
}
