package com.pocketdock;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Các hàm đổi dữ liệu thô trong cơ sở dữ liệu thành chữ dễ đọc cho giao diện. */
public final class UiText {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private UiText() { }

    /** Đổi thời điểm dạng ISO (UTC) sang giờ địa phương. Rỗng thì trả về dấu gạch ngang. */
    public static String time(String iso) {
        return time(iso, ZoneId.systemDefault());
    }

    public static String time(String iso, ZoneId zone) {
        if (iso == null || iso.isBlank()) return "—";
        try {
            return FORMAT.format(Instant.parse(iso).atZone(zone));
        } catch (DateTimeException e) {
            return iso;
        }
    }

    /** Thời lượng giữa hai thời điểm ISO. Chưa kết thúc thì ghi "đang mở". */
    public static String duration(String startIso, String endIso) {
        if (startIso == null || startIso.isBlank()) return "—";
        if (endIso == null || endIso.isBlank()) return "đang mở";
        try {
            long seconds = Math.max(0, Duration.between(Instant.parse(startIso), Instant.parse(endIso)).getSeconds());
            if (seconds < 60) return seconds + " giây";
            long minutes = seconds / 60;
            if (minutes < 60) return minutes + " phút " + (seconds % 60) + " giây";
            return (minutes / 60) + " giờ " + (minutes % 60) + " phút";
        } catch (DateTimeException e) {
            return "—";
        }
    }

    /** Lý do kết thúc phiên (mã lưu trong cơ sở dữ liệu) sang tiếng Việt. */
    public static String reason(String code) {
        if (code == null || code.isBlank()) return "Đang mở";
        return switch (code) {
            case "phone_left" -> "Điện thoại rời đi";
            case "desktop_disconnect" -> "Máy tính ngắt kết nối";
            case "app_closed" -> "Tắt ứng dụng";
            case "server_lost" -> "Mất kết nối máy chủ";
            case "interrupted" -> "Bị gián đoạn (tắt đột ngột)";
            case "closed" -> "Đóng";
            default -> code;
        };
    }

    /** Chất lượng màn hình hợp lệ: "720p" hoặc "1080p". Giá trị lạ thì dùng mặc định 720p. */
    public static String normalizeQuality(String value) {
        return "1080p".equals(value) ? "1080p" : "720p";
    }
}
