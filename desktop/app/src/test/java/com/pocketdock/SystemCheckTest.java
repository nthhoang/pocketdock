package com.pocketdock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pocketdock.SystemCheck.PublicKind;
import com.pocketdock.SystemCheck.Result;
import com.pocketdock.SystemCheck.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

class SystemCheckTest {

    @Test
    void diaChiHttpsCongKhai() {
        assertEquals(PublicKind.HTTPS, SystemCheck.classifyPublicUrl("https://abc.trycloudflare.com"));
        assertEquals(PublicKind.HTTPS, SystemCheck.classifyPublicUrl("https://abc.trycloudflare.com/"));
    }

    @Test
    void diaChiCuaChinMayNayLaLocal() {
        assertEquals(PublicKind.LOCAL, SystemCheck.classifyPublicUrl("http://localhost:8081"));
        assertEquals(PublicKind.LOCAL, SystemCheck.classifyPublicUrl("http://127.0.0.1:8081"));
        assertEquals(PublicKind.LOCAL, SystemCheck.classifyPublicUrl("https://localhost"));
    }

    @Test
    void httpNgoaiMayNayKhongAnToan() {
        assertEquals(PublicKind.HTTP_NOT_SECURE, SystemCheck.classifyPublicUrl("http://192.168.1.5:8081"));
    }

    @Test
    void diaChiSaiLaInvalid() {
        assertEquals(PublicKind.INVALID, SystemCheck.classifyPublicUrl(null));
        assertEquals(PublicKind.INVALID, SystemCheck.classifyPublicUrl(""));
        assertEquals(PublicKind.INVALID, SystemCheck.classifyPublicUrl("abc"));
        assertEquals(PublicKind.INVALID, SystemCheck.classifyPublicUrl("ftp://example.com"));
    }

    @Test
    void diaChiKiemTraSucKhoe() {
        assertEquals("http://localhost:8081/health", SystemCheck.healthUrl("http://localhost:8081"));
        assertEquals("https://abc.trycloudflare.com/health", SystemCheck.healthUrl("https://abc.trycloudflare.com/"));
        assertNull(SystemCheck.healthUrl("abc"));
        assertNull(SystemCheck.healthUrl(null));
    }

    @Test
    void mangAoKhongTinhLaWifi() {
        assertTrue(SystemCheck.isVirtualName("docker0"));
        assertTrue(SystemCheck.isVirtualName("br-1c3905bdf674"));
        assertTrue(SystemCheck.isVirtualName("veth12ab"));
        assertFalse(SystemCheck.isVirtualName("wlp8s0"));
        assertFalse(SystemCheck.isVirtualName("eth0"));
        assertFalse(SystemCheck.isVirtualName(null));
    }

    @Test
    void phienBanJava() {
        assertTrue(SystemCheck.javaAtLeast(21, 21));
        assertTrue(SystemCheck.javaAtLeast(25, 21));
        assertFalse(SystemCheck.javaAtLeast(17, 21));
    }

    @Test
    void baoCaoDemDungVaCoGoiY() {
        List<Result> results = List.of(
                new Result("Mục A", Status.OK, "tốt", ""),
                new Result("Mục B", Status.FAIL, "hỏng", "sửa như sau"),
                new Result("Mục C", Status.NA, "không áp dụng", ""));
        assertEquals("1 đạt, 0 cảnh báo, 1 lỗi, 1 không áp dụng", SystemCheck.summaryLine(results));

        String text = SystemCheck.toText(results);
        assertTrue(text.contains("[ĐẠT] Mục A"));
        assertTrue(text.contains("[LỖI] Mục B"));
        assertTrue(text.contains("-> sửa như sau"));
        assertTrue(text.contains("Tổng: 1 đạt, 0 cảnh báo, 1 lỗi, 1 không áp dụng"));
        // chỉ mục có gợi ý mới có dòng "->"
        assertEquals(1, text.split("->", -1).length - 1);
    }

    @Test
    void cacMucCuaWindowsBaoDungTheoHeDieuHanh() {
        List<Result> parts = SystemCheck.windowsParts();
        assertEquals(3, parts.size());
        Status expected = SystemCheck.isWindows() ? Status.WARN : Status.NA;
        for (Result r : parts) assertEquals(expected, r.status());
    }
}
