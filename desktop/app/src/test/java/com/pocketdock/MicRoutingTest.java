package com.pocketdock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MicRoutingTest {

    private static final List<String> NAMES = List.of(
            "Speakers (Realtek(R) Audio)",
            "CABLE In 16 Ch (VB-Audio Virtual Cable)",
            "CABLE Input (VB-Audio Virtual Cable)",
            "Headphones (USB Audio)");

    @Test
    void timDungCableInputBoQuaBan16Kenh() {
        assertEquals(2, MicRouting.choose(NAMES, MicRouting.DEFAULT_WANTED));
    }

    @Test
    void khongPhanBietHoaThuong() {
        assertEquals(2, MicRouting.choose(NAMES, "cable input"));
    }

    @Test
    void chonThietBiKhacBangTenTuyY() {
        assertEquals(3, MicRouting.choose(NAMES, "usb audio"));
    }

    @Test
    void khongCoThiTraAm1() {
        assertEquals(-1, MicRouting.choose(List.of("Speakers (Realtek(R) Audio)"), MicRouting.DEFAULT_WANTED));
        assertEquals(-1, MicRouting.choose(List.of(), MicRouting.DEFAULT_WANTED));
    }

    @Test
    void doiSoRongHoacNullThiTraAm1() {
        assertEquals(-1, MicRouting.choose(null, "CABLE Input"));
        assertEquals(-1, MicRouting.choose(NAMES, null));
        assertEquals(-1, MicRouting.choose(NAMES, "  "));
    }
}
