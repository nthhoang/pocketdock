package com.pocketdock;

import java.util.List;

/**
 * Chọn thiết bị phát để đưa tiếng micro từ điện thoại vào VB-Cable.
 * Ứng dụng họp (Zoom, Teams) chọn "CABLE Output" làm micro là nghe được tiếng này.
 * Mặc định chỉ bật trên Windows. Biến môi trường:
 *   POCKETDOCK_MIC_ROUTE = "real" ép bật, "off" ép tắt;
 *   POCKETDOCK_MIC_OUT   = đoạn tên thiết bị phát cần tìm (mặc định "CABLE Input").
 */
public final class MicRouting {

    public static final String DEFAULT_WANTED = "CABLE Input";

    private MicRouting() {}

    public static boolean enabled() {
        String mode = System.getenv("POCKETDOCK_MIC_ROUTE");
        if ("off".equalsIgnoreCase(mode) || "test".equalsIgnoreCase(mode)) return false;
        if ("real".equalsIgnoreCase(mode)) return true;
        return SystemCheck.isWindows();
    }

    public static String wanted() {
        String env = System.getenv("POCKETDOCK_MIC_OUT");
        return (env == null || env.isBlank()) ? DEFAULT_WANTED : env.trim();
    }

    /** Vị trí thiết bị đầu tiên có tên chứa đoạn cần tìm (không phân biệt hoa thường); -1 nếu không có. */
    public static int choose(List<String> names, String wanted) {
        if (names == null || wanted == null || wanted.isBlank()) return -1;
        String w = wanted.toLowerCase();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (n != null && n.toLowerCase().contains(w)) return i;
        }
        return -1;
    }
}
