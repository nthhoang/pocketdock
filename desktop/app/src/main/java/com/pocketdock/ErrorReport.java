package com.pocketdock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Báo cáo lỗi: gom kết quả kiểm tra hệ thống, thông tin máy và phần cuối nhật ký vào một file chữ để gửi cho người khác. */
public final class ErrorReport {

    private static final Path LOG_FILE = Path.of("ketqua", "m4-nhat-ky.txt");
    private static final int LOG_LINES = 200;

    private ErrorReport() {}

    /** Ghép nội dung báo cáo. Hàm thuần, có kiểm thử tự động. */
    static String build(String now, String os, String java, String micRoute, String checkText,
                        List<String> logTail, Map<String, String> pocketdockEnv) {
        StringBuilder sb = new StringBuilder();
        sb.append("PocketDock - báo cáo lỗi\n");
        sb.append("Thời gian: ").append(now).append('\n');
        sb.append("Hệ điều hành: ").append(os).append('\n');
        sb.append("Java: ").append(java).append('\n');
        sb.append("Nơi phát tiếng micro điện thoại: ").append(micRoute).append('\n');
        sb.append("\nBiến môi trường POCKETDOCK_*:\n");
        if (pocketdockEnv.isEmpty()) {
            sb.append("  (không đặt biến nào)\n");
        } else {
            for (Map.Entry<String, String> e : pocketdockEnv.entrySet()) {
                sb.append("  ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
        }
        sb.append("\n=== Kiểm tra hệ thống ===\n").append(checkText).append('\n');
        sb.append("=== Nhật ký (").append(logTail.size()).append(" dòng cuối) ===\n");
        for (String line : logTail) sb.append(line).append('\n');
        return sb.toString();
    }

    /** Lấy tối đa n dòng cuối của file; không có file thì trả danh sách rỗng. */
    static List<String> tail(Path file, int maxLines) {
        if (!Files.isRegularFile(file)) return new ArrayList<>();
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - maxLines);
            return new ArrayList<>(all.subList(from, all.size()));
        } catch (IOException e) {
            List<String> err = new ArrayList<>();
            err.add("(không đọc được nhật ký: " + e.getMessage() + ")");
            return err;
        }
    }

    /** Ghi báo cáo vào ketqua/bao-cao-loi-ngày-giờ.txt và trả về đường dẫn đầy đủ. */
    public static Path write(String checkText) throws IOException {
        LocalDateTime time = LocalDateTime.now();
        Map<String, String> env = new TreeMap<>();
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            if (e.getKey().startsWith("POCKETDOCK_")) env.put(e.getKey(), e.getValue());
        }
        String text = build(
                time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")",
                System.getProperty("java.version"),
                RtcRuntime.micRoute(),
                checkText,
                tail(LOG_FILE, LOG_LINES),
                env);
        Path dir = Path.of("ketqua");
        Files.createDirectories(dir);
        Path file = dir.resolve("bao-cao-loi-" + time.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt")
                .toAbsolutePath();
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }
}
