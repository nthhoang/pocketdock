package com.pocketdock;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Kiểm tra hệ thống: báo máy còn thiếu gì để PocketDock chạy được. */
public final class SystemCheck {

    public enum Status { OK, WARN, FAIL, NA }

    /** Một dòng kết quả: tên mục, trạng thái, chi tiết đã đo, và gợi ý cách sửa (có thể rỗng). */
    public record Result(String name, Status status, String detail, String hint) { }

    /** Thông tin do ứng dụng cung cấp cho bộ kiểm tra. db và screenInfo có thể là null. */
    public record Context(String serverUrl, String publicUrl, Database db, String screenInfo, Path logDir) { }

    public enum PublicKind { HTTPS, HTTP_NOT_SECURE, LOCAL, INVALID }

    private SystemCheck() { }

    public static List<Result> runAll(Context ctx) {
        List<Result> out = new ArrayList<>();
        out.add(safe("Phiên bản Java", SystemCheck::checkJava));
        out.add(safe("Hệ điều hành", SystemCheck::checkOs));
        out.add(safe("Thư viện WebRTC", SystemCheck::checkWebRtc));
        out.add(safe("Máy chủ hỗ trợ kết nối", () -> checkServer(ctx.serverUrl())));
        out.add(safe("Địa chỉ công khai cho điện thoại", () -> checkPublicUrl(ctx.publicUrl())));
        out.add(safe("Cơ sở dữ liệu", () -> checkDatabase(ctx.db())));
        out.add(safe("Thư mục nhật ký", () -> checkLogDir(ctx.logDir())));
        out.add(safe("Màn hình", () -> checkScreen(ctx.screenInfo())));
        out.add(safe("Mạng nội bộ (Wi-Fi)", SystemCheck::checkLan));
        out.addAll(windowsParts());
        return out;
    }

    private static Result safe(String name, Supplier<Result> check) {
        try {
            return check.get();
        } catch (Throwable t) {
            return new Result(name, Status.FAIL, "Không kiểm tra được: " + t, "");
        }
    }

    // ---------- các phần dùng được trên mọi hệ điều hành ----------

    static Result checkJava() {
        int feature = Runtime.version().feature();
        String detail = "Java " + System.getProperty("java.version");
        if (javaAtLeast(feature, 21)) return new Result("Phiên bản Java", Status.OK, detail, "");
        return new Result("Phiên bản Java", Status.FAIL, detail, "Cài JDK 21 trở lên (Temurin hoặc OpenJDK).");
    }

    static Result checkOs() {
        String detail = System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")";
        if (isWindows()) return new Result("Hệ điều hành", Status.OK, detail, "");
        return new Result("Hệ điều hành", Status.WARN, detail,
                "Bản chạy đầy đủ chỉ hỗ trợ Windows 10/11. Trên hệ điều hành này chỉ dùng để phát triển.");
    }

    /** Nạp thật thư viện mã gốc của WebRTC (cùng bộ nhà máy mà ứng dụng dùng khi nối). */
    static Result checkWebRtc() {
        try {
            RtcRuntime.factory();
            return new Result("Thư viện WebRTC", Status.OK, "Đã nạp được thư viện mã gốc webrtc-java", "");
        } catch (Throwable t) {
            return new Result("Thư viện WebRTC", Status.FAIL, "Không nạp được: " + t,
                    "Kiểm tra khai báo hai file jar của webrtc-java (jar Java và jar mã gốc theo hệ điều hành) trong build.gradle.kts.");
        }
    }

    static Result checkServer(String serverUrl) {
        String name = "Máy chủ hỗ trợ kết nối";
        String url = healthUrl(serverUrl);
        if (url == null) {
            return new Result(name, Status.FAIL, "Địa chỉ máy chủ không hợp lệ: " + serverUrl,
                    "Đặt lại biến môi trường POCKETDOCK_SERVER, ví dụ http://localhost:8081");
        }
        long start = System.nanoTime();
        int code = httpStatus(url, 3);
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (code == 200) return new Result(name, Status.OK, url + " trả lời trong " + ms + " ms", "");
        String detail = code < 0 ? url + " không trả lời" : url + " trả về mã " + code;
        return new Result(name, Status.FAIL, detail, "Chạy máy chủ: cd server && ./gradlew run");
    }

    static Result checkPublicUrl(String publicUrl) {
        String name = "Địa chỉ công khai cho điện thoại";
        switch (classifyPublicUrl(publicUrl)) {
            case INVALID:
                return new Result(name, Status.FAIL, "Địa chỉ không hợp lệ: " + publicUrl,
                        "Đặt biến POCKETDOCK_PUBLIC_URL, ví dụ https://xxxx.trycloudflare.com");
            case LOCAL:
                return new Result(name, Status.WARN, publicUrl + " chỉ là địa chỉ của máy này",
                        "Điện thoại không mở được. Chạy cloudflared tunnel --url http://localhost:8081 rồi đặt POCKETDOCK_PUBLIC_URL.");
            case HTTP_NOT_SECURE:
                return new Result(name, Status.WARN, publicUrl + " không dùng HTTPS",
                        "Điện thoại chỉ cho dùng camera và micro trên trang HTTPS.");
            default:
                String url = healthUrl(publicUrl);
                int code = httpStatus(url, 5);
                if (code == 200) return new Result(name, Status.OK, url + " mở được", "");
                String detail = code < 0 ? url + " không mở được" : url + " trả về mã " + code;
                return new Result(name, Status.FAIL, detail,
                        "Kiểm tra cloudflared còn chạy không và địa chỉ có đúng địa chỉ mới nhất không.");
        }
    }

    static Result checkDatabase(Database db) {
        String name = "Cơ sở dữ liệu";
        if (db == null) {
            return new Result(name, Status.FAIL, "Chưa mở được cơ sở dữ liệu",
                    "Xem dòng \"Khong mo duoc CSDL\" trong nhật ký. Đường dẫn mặc định: ~/.pocketdock/pocketdock.db");
        }
        return new Result(name, Status.OK,
                db.path() + ", " + db.countDevices() + " thiết bị, " + db.countSessions() + " phiên", "");
    }

    static Result checkLogDir(Path dir) {
        String name = "Thư mục nhật ký";
        if (dir == null) return new Result(name, Status.WARN, "Không rõ thư mục nhật ký", "");
        try {
            Files.createDirectories(dir);
            if (Files.isWritable(dir)) return new Result(name, Status.OK, dir + " ghi được", "");
            return new Result(name, Status.FAIL, dir + " không ghi được", "Cấp quyền ghi cho thư mục này.");
        } catch (Exception e) {
            return new Result(name, Status.FAIL, dir + ": " + e.getMessage(), "Cấp quyền ghi cho thư mục này.");
        }
    }

    static Result checkScreen(String screenInfo) {
        if (screenInfo == null || screenInfo.isBlank()) {
            return new Result("Màn hình", Status.WARN, "Không đọc được thông tin màn hình", "");
        }
        return new Result("Màn hình", Status.OK, screenInfo, "");
    }

    static Result checkLan() {
        String name = "Mạng nội bộ (Wi-Fi)";
        List<String> found = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || isVirtualName(nic.getName())) continue;
                for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        found.add(nic.getName() + ": " + addr.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            return new Result(name, Status.WARN, "Không đọc được danh sách mạng: " + e.getMessage(), "");
        }
        if (found.isEmpty()) {
            return new Result(name, Status.WARN, "Không thấy mạng nội bộ nào đang hoạt động",
                    "Điện thoại và máy tính cần cùng một mạng Wi-Fi (cho đến khi có máy chủ TURN).");
        }
        return new Result(name, Status.OK, String.join(", ", found), "");
    }

    // ---------- các phần chỉ có ý nghĩa trên Windows (sẽ làm ở M6) ----------

    static List<Result> windowsParts() {
        if (isWindows()) return WindowsChecks.all();
        String[][] parts = {
                {"Chụp màn hình thật và điều khiển chuột", "Cần bộ chụp màn hình và Robot (làm ở M6)."},
                {"Âm thanh máy tính (WASAPI) và VB-Cable", "Cần chương trình lấy âm thanh C# và VB-Cable (làm ở M6)."},
                {"Webcam ảo (OBS Virtual Camera)", "Cần cài OBS Studio để đưa webcam vào Zoom hoặc Teams."},
        };
        List<Result> out = new ArrayList<>();
        for (String[] p : parts) {
            if (isWindows()) {
                out.add(new Result(p[0], Status.WARN, "Chưa có bộ kiểm tra cho mục này", p[1]));
            } else {
                out.add(new Result(p[0], Status.NA, "Chỉ áp dụng trên Windows", ""));
            }
        }
        return out;
    }

    // ---------- hàm thuần, có test tự động ----------

    public static boolean javaAtLeast(int feature, int min) {
        return feature >= min;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Tên card mạng ảo (Docker, cầu nối, máy ảo) không phải mạng Wi-Fi mà điện thoại dùng. */
    static boolean isVirtualName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("docker") || n.startsWith("br-") || n.startsWith("veth")
                || n.startsWith("virbr") || n.startsWith("vmnet") || n.startsWith("vboxnet");
    }

    /** Phân loại địa chỉ công khai mà điện thoại sẽ mở. */
    public static PublicKind classifyPublicUrl(String url) {
        if (url == null || url.isBlank()) return PublicKind.INVALID;
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return PublicKind.INVALID;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || scheme == null) return PublicKind.INVALID;
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return PublicKind.INVALID;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]")
                || h.endsWith(".local")) {
            return PublicKind.LOCAL;
        }
        return scheme.equalsIgnoreCase("https") ? PublicKind.HTTPS : PublicKind.HTTP_NOT_SECURE;
    }

    /** Địa chỉ kiểm tra sức khỏe của máy chủ, hoặc null nếu địa chỉ gốc không hợp lệ. */
    public static String healthUrl(String baseUrl) {
        if (baseUrl == null) return null;
        String b = baseUrl.trim();
        if (b.isEmpty() || classifyPublicUrl(b) == PublicKind.INVALID) return null;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + "/health";
    }

    private static int httpStatus(String url, int timeoutSeconds) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String statusLabel(Status s) {
        return switch (s) {
            case OK -> "[ĐẠT]";
            case WARN -> "[CẢNH BÁO]";
            case FAIL -> "[LỖI]";
            case NA -> "[KHÔNG ÁP DỤNG]";
        };
    }

    public static int count(List<Result> results, Status status) {
        int n = 0;
        for (Result r : results) if (r.status() == status) n++;
        return n;
    }

    public static String summaryLine(List<Result> results) {
        return count(results, Status.OK) + " đạt, " + count(results, Status.WARN) + " cảnh báo, "
                + count(results, Status.FAIL) + " lỗi, " + count(results, Status.NA) + " không áp dụng";
    }

    /** Báo cáo dạng chữ, dùng để hiện trên màn hình và để gửi cho người khác. */
    public static String toText(List<Result> results) {
        StringBuilder sb = new StringBuilder();
        for (Result r : results) {
            sb.append(statusLabel(r.status())).append(' ').append(r.name()).append('\n');
            sb.append("    ").append(r.detail()).append('\n');
            if (!r.hint().isEmpty()) sb.append("    -> ").append(r.hint()).append('\n');
        }
        sb.append('\n').append("Tổng: ").append(summaryLine(results)).append('\n');
        return sb.toString();
    }
}
