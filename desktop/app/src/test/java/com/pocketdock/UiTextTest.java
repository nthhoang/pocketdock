package com.pocketdock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class UiTextTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Test
    void thoiDiemDoiSangGioDiaPhuong() {
        // 13:30:34 UTC là 20:30:34 ở Việt Nam (đúng như dữ liệu thật đã ghi)
        assertEquals("03/11/2026 20:30:34", UiText.time("2026-11-03T13:30:34.025989070Z", VN));
    }

    @Test
    void thoiDiemRongHoacSaiThiKhongLoi() {
        assertEquals("—", UiText.time(null, VN));
        assertEquals("—", UiText.time("", VN));
        assertEquals("khong-phai-gio", UiText.time("khong-phai-gio", VN));
    }

    @Test
    void thoiLuong() {
        assertEquals("12 giây", UiText.duration("2026-11-03T13:30:34Z", "2026-11-03T13:30:46Z"));
        assertEquals("2 phút 5 giây", UiText.duration("2026-11-03T13:30:00Z", "2026-11-03T13:32:05Z"));
        assertEquals("1 giờ 3 phút", UiText.duration("2026-11-03T13:00:00Z", "2026-11-03T14:03:30Z"));
        assertEquals("đang mở", UiText.duration("2026-11-03T13:30:34Z", null));
        assertEquals("—", UiText.duration(null, null));
    }

    @Test
    void lyDoKetThuc() {
        assertEquals("Đang mở", UiText.reason(null));
        assertEquals("Điện thoại rời đi", UiText.reason("phone_left"));
        assertEquals("Máy tính ngắt kết nối", UiText.reason("desktop_disconnect"));
        assertEquals("Tắt ứng dụng", UiText.reason("app_closed"));
        assertEquals("Mất kết nối máy chủ", UiText.reason("server_lost"));
        assertEquals("Bị gián đoạn (tắt đột ngột)", UiText.reason("interrupted"));
        assertEquals("ma_la", UiText.reason("ma_la"));
    }

    @Test
    void chatLuongManHinh() {
        assertEquals("720p", UiText.normalizeQuality(null));
        assertEquals("720p", UiText.normalizeQuality("abc"));
        assertEquals("720p", UiText.normalizeQuality("720p"));
        assertEquals("1080p", UiText.normalizeQuality("1080p"));
    }
}
