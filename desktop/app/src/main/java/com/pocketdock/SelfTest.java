package com.pocketdock;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Chế độ "PocketDock.exe --selftest": chạy bộ kiểm tra hệ thống không cần mở giao diện,
 * in báo cáo (không dấu, để console Windows không lỗi font) và ghi bản đầy đủ vào ketqua/selftest.txt.
 * Mã thoát: 0 nếu không có mục lỗi, 1 nếu có.
 */
public final class SelfTest {

    private SelfTest() {}

    public static boolean requested(String[] args) {
        if (args == null) return false;
        for (String a : args) {
            if ("--selftest".equals(a)) return true;
        }
        return false;
    }

    public static int run() {
        String server = env("POCKETDOCK_SERVER", "http://localhost:8081");
        String publicUrl = env("POCKETDOCK_PUBLIC_URL", server);
        Database db = null;
        try {
            db = Database.open(Database.defaultPath());
        } catch (RuntimeException e) {
            db = null; // mục "Cơ sở dữ liệu" sẽ báo lỗi
        }
        SystemCheck.Context ctx = new SystemCheck.Context(server, publicUrl, db, awtScreenInfo(),
                Path.of("ketqua").toAbsolutePath());
        List<SystemCheck.Result> results = SystemCheck.runAll(ctx);

        String report = "PocketDock --selftest, "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n\n"
                + SystemCheck.toText(results);
        try {
            Path file = Path.of("ketqua", "selftest.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("Khong ghi duoc ketqua/selftest.txt: " + e.getMessage());
        }
        System.out.println(Ascii.fold(report));
        if (db != null) db.close();
        return SystemCheck.count(results, SystemCheck.Status.FAIL) > 0 ? 1 : 0;
    }

    private static String awtScreenInfo() {
        try {
            if (GraphicsEnvironment.isHeadless()) return null;
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            Rectangle b = gc.getBounds();
            return b.width + "x" + b.height + ", phóng " + Math.round(gc.getDefaultTransform().getScaleX() * 100) + "%";
        } catch (Throwable t) {
            return null;
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }
}
