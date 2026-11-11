package com.pocketdock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErrorReportTest {

    @Test
    void baoCaoCoDuCacPhan() {
        Map<String, String> env = new TreeMap<>();
        env.put("POCKETDOCK_SERVER", "http://localhost:8081");
        String text = ErrorReport.build("2026-11-11 10:00:00", "Windows 11", "21.0.7", "Loa mặc định",
                "[LỖI] Máy chủ\n", List.of("10:00:01  dong nhat ky"), env);
        assertTrue(text.contains("Thời gian: 2026-11-11 10:00:00"));
        assertTrue(text.contains("Hệ điều hành: Windows 11"));
        assertTrue(text.contains("POCKETDOCK_SERVER=http://localhost:8081"));
        assertTrue(text.contains("=== Kiểm tra hệ thống ===\n[LỖI] Máy chủ"));
        assertTrue(text.contains("=== Nhật ký (1 dòng cuối) ===\n10:00:01  dong nhat ky"));
    }

    @Test
    void khongCoBienMoiTruong() {
        String text = ErrorReport.build("t", "os", "java", "route", "check", List.of(), Map.of());
        assertTrue(text.contains("(không đặt biến nào)"));
    }

    @Test
    void layDongCuoiCuaNhatKy(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("log.txt");
        Files.write(log, List.of("1", "2", "3", "4", "5"));
        assertEquals(List.of("4", "5"), ErrorReport.tail(log, 2));
        assertEquals(List.of("1", "2", "3", "4", "5"), ErrorReport.tail(log, 100));
    }

    @Test
    void khongCoFileNhatKyThiRong(@TempDir Path dir) {
        assertTrue(ErrorReport.tail(dir.resolve("khong-co.txt"), 10).isEmpty());
    }
}
