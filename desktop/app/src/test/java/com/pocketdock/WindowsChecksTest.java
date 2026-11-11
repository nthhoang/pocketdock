package com.pocketdock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowsChecksTest {

    @Test
    void probeThanhCong() {
        assertEquals("thiet bi=Speakers; dinh dang=48000 Hz, 2 kenh, 32 bit",
                WindowsChecks.probeVerdict("OK thiet bi=Speakers; dinh dang=48000 Hz, 2 kenh, 32 bit\r\n"));
    }

    @Test
    void probeLoiHoacRongThiNull() {
        assertNull(WindowsChecks.probeVerdict("LOI Khong co thiet bi"));
        assertNull(WindowsChecks.probeVerdict(""));
        assertNull(WindowsChecks.probeVerdict(null));
        assertNull(WindowsChecks.probeVerdict("OKAY"));
    }

    @Test
    void timCameraObs() {
        assertEquals("OBS Virtual Camera",
                WindowsChecks.obsListed(List.of("Integrated Camera", "OBS Virtual Camera")));
        assertEquals("obs virtual camera", WindowsChecks.obsListed(List.of("obs virtual camera")));
    }

    @Test
    void khongCoCameraObsThiNull() {
        assertNull(WindowsChecks.obsListed(List.of("Integrated Camera")));
        assertNull(WindowsChecks.obsListed(List.of()));
        assertNull(WindowsChecks.obsListed(null));
        assertNull(WindowsChecks.obsListed(Arrays.asList("A", null)));
    }
}
