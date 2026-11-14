package com.pocketdock;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Chế độ "PocketDock.exe --auto": tự bật máy chủ (bản đóng gói đi kèm) và đường hầm HTTPS cloudflared,
 * lấy địa chỉ trycloudflare.com cho điện thoại rồi cho ứng dụng dùng địa chỉ đó. Đóng ứng dụng thì tắt cả hai.
 * Không cần gõ lệnh từng bước như khi phát triển.
 *
 * Biến môi trường: POCKETDOCK_AUTO=1 (như --auto), POCKETDOCK_SERVER_HOME (thư mục máy chủ đã build),
 * POCKETDOCK_CLOUDFLARED (đường dẫn cloudflared), PORT (mặc định 8081).
 */
public final class AutoStack {

    static final String SERVER_SCRIPT = "pocketdock-server";
    private static final String CLOUDFLARED_WINDOWS_URL =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe";

    private static volatile String serverUrl;
    private static volatile String publicUrl;
    private static volatile Process serverProcess;
    private static volatile Process tunnelProcess;
    private static volatile boolean hookAdded;

    private AutoStack() {}

    // ---------- giao diện cho phần còn lại của ứng dụng ----------

    public static boolean requested(String[] args) {
        return requested(args, System.getenv());
    }

    static boolean requested(String[] args, Map<String, String> env) {
        if (args != null) {
            for (String a : args) {
                if ("--auto".equals(a)) return true;
            }
        }
        String v = env.get("POCKETDOCK_AUTO");
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    /** Giá trị thay cho biến môi trường POCKETDOCK_SERVER và POCKETDOCK_PUBLIC_URL khi đã chạy --auto; không thì null. */
    public static String override(String key) {
        if ("POCKETDOCK_SERVER".equals(key)) return serverUrl;
        if ("POCKETDOCK_PUBLIC_URL".equals(key)) return publicUrl;
        return null;
    }

    public static void start() throws IOException {
        start(System.getenv());
    }

    public static void stop() {
        serverUrl = null;
        publicUrl = null;
        Process t = tunnelProcess;
        tunnelProcess = null;
        kill(t);
        Process s = serverProcess;
        serverProcess = null;
        kill(s);
    }

    // ---------- chạy ----------

    static void start(Map<String, String> env) throws IOException {
        if (!hookAdded) {
            hookAdded = true;
            Runtime.getRuntime().addShutdownHook(new Thread(AutoStack::stop, "autostack-stop"));
        }
        int port = parsePort(env.get("PORT"), 8081);
        String local = "http://localhost:" + port;
        log("Tu dong: bat may chu va duong ham HTTPS cho dien thoai");

        if (httpOk(local + "/health", 1500)) {
            log("Tu dong: may chu da chay san tai " + local + ", dung lai");
        } else {
            startServer(env, port);
            if (!waitHealth(local, 40_000, serverProcess)) {
                throw new IOException("May chu khong tra loi sau 40 giay (xem ketqua/may-chu.log)");
            }
            log("Tu dong: may chu da san sang tai " + local);
        }

        Path cloudflared = findCloudflared(env);
        String url = startTunnel(cloudflared, port);
        log("Tu dong: dia chi cho dien thoai: " + url);
        if (!"1".equals(env.get("POCKETDOCK_AUTO_NO_PUBLIC_CHECK"))) {
            if (!waitHealth(url, 60_000, tunnelProcess)) {
                throw new IOException("Dia chi " + url + " chua mo duoc sau 60 giay, hay kiem tra Internet");
            }
        }
        serverUrl = local;
        publicUrl = url;
        log("Tu dong: xong, ung dung se dung " + url);
    }

    private static void startServer(Map<String, String> env, int port) throws IOException {
        Path home = firstServerHome(serverHomeCandidates(env));
        if (home == null) {
            throw new IOException("Khong tim thay thu muc may chu. Khi phat trien hay chay truoc: cd server && ./gradlew installDist");
        }
        String script = SystemCheck.isWindows() ? SERVER_SCRIPT + ".bat" : SERVER_SCRIPT;
        ProcessBuilder pb = new ProcessBuilder(home.resolve("bin").resolve(script).toString());
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        pb.environment().put("PORT", String.valueOf(port));
        String webEnv = env.get("WEB_DIR");
        if (webEnv == null || webEnv.isBlank()) {
            Path web = firstWebDir(webDirCandidates(home, env));
            if (web != null) {
                pb.environment().put("WEB_DIR", web.toString());
            } else {
                log("Tu dong: khong thay thu muc web, may chu se tim theo cach mac dinh");
            }
        }
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath("may-chu.log").toFile()));
        serverProcess = pb.start();
        log("Tu dong: da chay may chu tu " + home);
    }

    private static String startTunnel(Path cloudflared, int port) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cloudflared.toString(), "tunnel", "--url", "http://localhost:" + port);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        tunnelProcess = p;
        CompletableFuture<String> found = new CompletableFuture<>();
        Path logFile = logPath("duong-ham.log");
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 var out = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                         StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.write(line);
                    out.newLine();
                    out.flush();
                    String u = TunnelUrl.find(line);
                    if (u != null) found.complete(u);
                }
            } catch (IOException ignored) {
                // ống dẫn đóng
            }
            found.completeExceptionally(new IOException("cloudflared da thoat"));
        }, "cloudflared-out");
        reader.setDaemon(true);
        reader.start();
        try {
            return found.get(45, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Het 45 giay van chua co dia chi tu cloudflared (xem ketqua/duong-ham.log)");
        } catch (ExecutionException e) {
            throw new IOException("cloudflared thoat som, xem ketqua/duong-ham.log");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Bi ngat khi cho cloudflared");
        }
    }

    // ---------- tìm các thành phần ----------

    private static Path findCloudflared(Map<String, String> env) throws IOException {
        String exe = SystemCheck.isWindows() ? "cloudflared.exe" : "cloudflared";
        List<Path> candidates = new ArrayList<>();
        String forced = env.get("POCKETDOCK_CLOUDFLARED");
        if (forced != null && !forced.isBlank()) candidates.add(Path.of(forced));
        candidates.add(toolsDir().resolve(exe));
        Path bundle = bundleDir();
        if (bundle != null) candidates.add(bundle.resolve("tools").resolve(exe));
        candidates.add(cwd().resolve("tools").resolve(exe));
        String pathVar = env.get("PATH") != null ? env.get("PATH") : env.get("Path");
        if (pathVar != null) {
            for (String dir : pathVar.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                try {
                    candidates.add(Path.of(dir.trim()).resolve(exe));
                } catch (RuntimeException ignored) {
                    // mục PATH không hợp lệ
                }
            }
        }
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return p.toAbsolutePath().normalize();
        }
        if (SystemCheck.isWindows()) return downloadCloudflared(toolsDir().resolve(exe));
        throw new IOException("Chua thay cloudflared. Hay cai cloudflared hoac dat POCKETDOCK_CLOUDFLARED");
    }

    private static Path downloadCloudflared(Path target) throws IOException {
        log("Tu dong: chua co cloudflared, dang tai tu GitHub (lan dau co the mat vai phut)");
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(CLOUDFLARED_WINDOWS_URL))
                .timeout(Duration.ofMinutes(5)).GET().build();
        try {
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
            if (response.statusCode() != 200 || Files.size(tmp) < 1_000_000) {
                Files.deleteIfExists(tmp);
                throw new IOException("Tai cloudflared khong thanh cong (ma " + response.statusCode() + ")");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Bi ngat khi tai cloudflared");
        }
        log("Tu dong: da tai cloudflared vao " + target);
        return target;
    }

    static List<Path> serverHomeCandidates(Map<String, String> env) {
        List<Path> list = new ArrayList<>();
        String forced = env.get("POCKETDOCK_SERVER_HOME");
        if (forced != null && !forced.isBlank()) list.add(Path.of(forced));
        Path bundle = bundleDir();
        if (bundle != null) list.add(bundle.resolve("server"));
        Path cwd = cwd();
        list.add(cwd.resolve("server"));
        String build = "server/build/install/pocketdock-server";
        list.add(cwd.resolve("../../" + build));
        list.add(cwd.resolve("../" + build));
        list.add(cwd.resolve(build));
        return list;
    }

    /** Thư mục máy chủ hợp lệ: có lib/ và bin/pocketdock-server (hoặc .bat). */
    static Path firstServerHome(List<Path> candidates) {
        for (Path p : candidates) {
            Path bin = p.resolve("bin");
            if (Files.isDirectory(p.resolve("lib"))
                    && (Files.isRegularFile(bin.resolve(SERVER_SCRIPT)) || Files.isRegularFile(bin.resolve(SERVER_SCRIPT + ".bat")))) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    static List<Path> webDirCandidates(Path serverHome, Map<String, String> env) {
        List<Path> list = new ArrayList<>();
        list.add(serverHome.resolve("../web"));
        list.add(serverHome.resolve("../../../../web")); // khi phát triển: <kho>/server/build/install/pocketdock-server
        Path bundle = bundleDir();
        if (bundle != null) list.add(bundle.resolve("web"));
        Path cwd = cwd();
        list.add(cwd.resolve("web"));
        list.add(cwd.resolve("../web"));
        list.add(cwd.resolve("../../web"));
        return list;
    }

    /** Thư mục trang web hợp lệ: có index.html. */
    static Path firstWebDir(List<Path> candidates) {
        for (Path p : candidates) {
            if (Files.isRegularFile(p.resolve("index.html"))) return p.toAbsolutePath().normalize();
        }
        return null;
    }

    // ---------- tiện ích ----------

    static int parsePort(String value, int fallback) {
        try {
            int p = Integer.parseInt(value == null ? "" : value.trim());
            return (p > 0 && p < 65536) ? p : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Thư mục PocketDock khi chạy bản đóng gói (cha của thư mục runtime); không phải bản đóng gói thì null. */
    private static Path bundleDir() {
        String home = System.getProperty("java.home");
        if (home == null) return null;
        Path runtime = Path.of(home);
        return (runtime.getFileName() != null && "runtime".equalsIgnoreCase(runtime.getFileName().toString()))
                ? runtime.getParent() : null;
    }

    private static Path cwd() {
        return Path.of(System.getProperty("user.dir", "."));
    }

    private static Path toolsDir() {
        return Path.of(System.getProperty("user.home"), ".pocketdock", "tools");
    }

    private static Path logPath(String name) throws IOException {
        Path dir = Path.of("ketqua");
        Files.createDirectories(dir);
        return dir.resolve(name);
    }

    static boolean httpOk(String url, int timeoutMs) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Chờ địa chỉ /health trả lời 200; thôi sớm nếu tiến trình theo dõi đã thoát. */
    private static boolean waitHealth(String base, long timeoutMs, Process watch) {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < end) {
            if (httpOk(base + "/health", 2000)) return true;
            if (watch != null && !watch.isAlive()) return false;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void kill(Process p) {
        if (p == null) return;
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    private static void log(String text) {
        EventLog.add(text);
        System.out.println(text);
    }
}
