package com.pocketdock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoStackTest {

    @Test
    void nhanRaYeuCauAuto() {
        assertTrue(AutoStack.requested(new String[] {"--auto"}, Map.of()));
        assertTrue(AutoStack.requested(new String[] {}, Map.of("POCKETDOCK_AUTO", "1")));
        assertFalse(AutoStack.requested(new String[] {"--selftest"}, Map.of()));
        assertFalse(AutoStack.requested(null, Map.of()));
    }

    @Test
    void docCong() {
        assertEquals(8081, AutoStack.parsePort(null, 8081));
        assertEquals(8081, AutoStack.parsePort("abc", 8081));
        assertEquals(8081, AutoStack.parsePort("70000", 8081));
        assertEquals(9000, AutoStack.parsePort(" 9000 ", 8081));
    }

    @Test
    void timThuMucMayChuHopLe(@TempDir Path dir) throws IOException {
        Path khongHopLe = Files.createDirectories(dir.resolve("a"));
        Path hopLe = Files.createDirectories(dir.resolve("b/lib"));
        Files.createDirectories(dir.resolve("b/bin"));
        Files.createFile(dir.resolve("b/bin/pocketdock-server"));
        assertEquals(dir.resolve("b").toAbsolutePath().normalize(),
                AutoStack.firstServerHome(List.of(khongHopLe, dir.resolve("b"))));
        assertNull(AutoStack.firstServerHome(List.of(khongHopLe)));
        assertNotNull(hopLe);
    }

    @Test
    void timThuMucWebCoIndexHtml(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("w1"));
        Files.createDirectories(dir.resolve("w2"));
        Files.createFile(dir.resolve("w2/index.html"));
        assertEquals(dir.resolve("w2").toAbsolutePath().normalize(),
                AutoStack.firstWebDir(List.of(dir.resolve("w1"), dir.resolve("w2"))));
        assertNull(AutoStack.firstWebDir(List.of(dir.resolve("w1"))));
    }

    /** Chạy đủ luồng với máy chủ giả (python http.server) và cloudflared giả; chỉ chạy trên Linux có python3. */
    @Test
    void chayDuLuongVoiMayChuGiaVaCloudflaredGia(@TempDir Path dir) throws Exception {
        Assumptions.assumeFalse(SystemCheck.isWindows());
        Assumptions.assumeTrue(commandExists("python3"));

        Path home = Files.createDirectories(dir.resolve("server-gia"));
        Files.createDirectories(home.resolve("lib"));
        Files.createDirectories(home.resolve("bin"));
        write(home.resolve("bin/pocketdock-server"),
                "#!/bin/sh\n"
                + "cd \"$(dirname \"$0\")/..\"\n"
                + "mkdir -p www && echo ok > www/health\n"
                + "exec python3 -m http.server \"$PORT\" --directory www\n");
        Path cloudflared = dir.resolve("cloudflared-gia");
        write(cloudflared,
                "#!/bin/sh\n"
                + "echo 'INF Requesting new quick Tunnel on trycloudflare.com...'\n"
                + "echo 'INF |  https://demo-gia-chay-thu.trycloudflare.com  |'\n"
                + "exec sleep 300\n");
        Path web = Files.createDirectories(dir.resolve("web"));
        Files.createFile(web.resolve("index.html"));

        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Map<String, String> env = Map.of(
                "PORT", String.valueOf(port),
                "POCKETDOCK_SERVER_HOME", home.toString(),
                "POCKETDOCK_CLOUDFLARED", cloudflared.toString(),
                "POCKETDOCK_AUTO_NO_PUBLIC_CHECK", "1",
                "WEB_DIR", web.toString());
        try {
            assertFalse(AutoStack.httpOk("http://localhost:" + port + "/health", 500));
            AutoStack.start(env);
            assertEquals("https://demo-gia-chay-thu.trycloudflare.com", AutoStack.override("POCKETDOCK_PUBLIC_URL"));
            assertEquals("http://localhost:" + port, AutoStack.override("POCKETDOCK_SERVER"));
            assertTrue(AutoStack.httpOk("http://localhost:" + port + "/health", 2000));
        } finally {
            AutoStack.stop();
        }
        assertNull(AutoStack.override("POCKETDOCK_PUBLIC_URL"));
        Thread.sleep(500);
        assertFalse(AutoStack.httpOk("http://localhost:" + port + "/health", 500));
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static boolean commandExists(String name) {
        try {
            return new ProcessBuilder("sh", "-c", "command -v " + name).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
