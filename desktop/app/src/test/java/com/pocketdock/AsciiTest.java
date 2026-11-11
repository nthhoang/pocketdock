package com.pocketdock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AsciiTest {

    @Test
    void boDauTiengViet() {
        assertEquals("Kiem tra he thong dat", Ascii.fold("Kiểm tra hệ thống đạt"));
        assertEquals("Da Nang", Ascii.fold("Đà Nẵng"));
        assertEquals("dong 1: loi", Ascii.fold("đòng 1: lỗi"));
    }

    @Test
    void chuKhongDauGiuNguyen() {
        assertEquals("PocketDock 1.0 --selftest", Ascii.fold("PocketDock 1.0 --selftest"));
    }

    @Test
    void nullThanhChuoiRong() {
        assertEquals("", Ascii.fold(null));
    }
}
