package com.pocketdock;

import dev.onvoid.webrtc.media.audio.CustomAudioSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Lấy âm thanh đang phát của máy tính qua chương trình phụ PocketDock.AudioHelper.exe (C#, WASAPI loopback)
 * rồi đẩy vào CustomAudioSource. Chương trình phụ ghi ra stdout từng khúc 10 ms, 16-bit, 48 kHz, 2 kênh (1920 byte).
 * Mặc định chỉ bật trên Windows. Biến môi trường POCKETDOCK_AUDIO: "real" ép bật, "test" ép dùng tiếng bíp.
 * POCKETDOCK_AUDIO_HELPER: đường dẫn tới chương trình phụ (dùng để thử bằng chương trình phụ giả trên Ubuntu).
 */
public final class LoopbackAudio {

    public static final String HELPER_EXE = "PocketDock.AudioHelper.exe";
    static final int CHUNK_BYTES = 1920;
    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    private static final int FRAMES = 480; // 10 ms ở 48 kHz
    private static final int MAX_RESTARTS = 3;

    private final CustomAudioSource source;
    private final Path helper;
    private Process process;
    private Thread reader;
    private volatile boolean stopping;
    private int restarts;
    private long generation;
    private boolean pushErrorLogged;

    private LoopbackAudio(CustomAudioSource source, Path helper) {
        this.source = source;
        this.helper = helper;
    }

    public static boolean enabled() {
        String mode = System.getenv("POCKETDOCK_AUDIO");
        if ("test".equalsIgnoreCase(mode)) return false;
        if ("real".equalsIgnoreCase(mode)) return true;
        return SystemCheck.isWindows();
    }

    /** Trả về null nếu không bật hoặc không thấy chương trình phụ (khi đó dùng tiếng bíp như cũ). */
    public static LoopbackAudio tryCreate(CustomAudioSource source) {
        if (!enabled()) return null;
        Path exe = firstExisting(candidates());
        if (exe == null) {
            EventLog.add("Am thanh: khong thay " + HELPER_EXE + ", dung tieng bip");
            return null;
        }
        EventLog.add("Am thanh: dung chuong trinh phu " + exe);
        return new LoopbackAudio(source, exe);
    }

    static List<Path> candidates() {
        List<Path> list = new ArrayList<>();
        String env = System.getenv("POCKETDOCK_AUDIO_HELPER");
        if (env != null && !env.isBlank()) list.add(Path.of(env));
        String home = System.getProperty("java.home");
        if (home != null) {
            Path parent = Path.of(home).getParent(); // thư mục PocketDock khi chạy bản đóng gói jpackage
            if (parent != null) list.add(parent.resolve("audio-helper").resolve(HELPER_EXE));
        }
        Path cwd = Path.of(System.getProperty("user.dir", "."));
        list.add(cwd.resolve("audio-helper").resolve(HELPER_EXE));
        list.add(cwd.resolve("../audio-helper/publish").resolve(HELPER_EXE));
        return list;
    }

    static Path firstExisting(List<Path> paths) {
        for (Path p : paths) {
            if (Files.isRegularFile(p)) return p.toAbsolutePath().normalize();
        }
        return null;
    }

    /** Đọc đúng từng khúc CHUNK_BYTES cho đến hết dữ liệu; phần lẻ cuối bị bỏ. Trả về số khúc đã đọc. */
    static long pumpChunks(InputStream in, Consumer<byte[]> sink) throws IOException {
        byte[] buf = new byte[CHUNK_BYTES];
        long chunks = 0;
        while (true) {
            int off = 0;
            while (off < CHUNK_BYTES) {
                int n = in.read(buf, off, CHUNK_BYTES - off);
                if (n < 0) return chunks;
                off += n;
            }
            sink.accept(buf);
            chunks++;
        }
    }

    public synchronized void start() {
        if (process != null && process.isAlive()) return;
        stopping = false;
        restarts = 0;
        launch();
    }

    private synchronized void launch() {
        try {
            Process p = new ProcessBuilder(helper.toString()).start();
            process = p;
            long gen = ++generation;
            reader = daemon("audio-helper-out", () -> pump(p, gen));
            daemon("audio-helper-err", () -> logErrors(p));
            EventLog.add("Am thanh: da chay chuong trinh phu lay am thanh may tinh");
        } catch (IOException e) {
            EventLog.add("Am thanh: khong chay duoc chuong trinh phu: " + e);
        }
    }

    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void pump(Process p, long gen) {
        long chunks = 0;
        try (InputStream in = p.getInputStream()) {
            chunks = pumpChunks(in, chunk -> {
                try {
                    // Âm thanh phải được đẩy từ đúng một luồng: chỉ luồng này gọi pushAudio
                    source.pushAudio(chunk, 16, SAMPLE_RATE, CHANNELS, FRAMES);
                } catch (Throwable t) {
                    if (!pushErrorLogged) {
                        pushErrorLogged = true;
                        EventLog.add("Am thanh: loi day vao WebRTC: " + t);
                    }
                }
            });
        } catch (IOException ignored) {
            // ống dẫn đóng
        }
        onExit(gen, chunks);
    }

    private void logErrors(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                EventLog.add("Am thanh (phu): " + line);
            }
        } catch (IOException ignored) {
            // ống dẫn đóng
        }
    }

    private void onExit(long gen, long chunks) {
        int attempt;
        synchronized (this) {
            if (stopping || gen != generation) return;
            if (chunks >= 1000) restarts = 0; // đã chạy ổn khoảng 10 giây thì tính lại
            if (restarts >= MAX_RESTARTS) {
                EventLog.add("Am thanh: chuong trinh phu dung nhieu lan, ngung thu lai");
                return;
            }
            attempt = ++restarts;
        }
        EventLog.add("Am thanh: chuong trinh phu dung bat ngo, chay lai sau 1 giay (lan " + attempt + ")");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (this) {
            if (!stopping && gen == generation) launch();
        }
    }

    public void stop() {
        Process p;
        Thread r;
        synchronized (this) {
            stopping = true;
            p = process;
            r = reader;
            process = null;
            reader = null;
            generation++;
        }
        if (p == null) return;
        p.destroy();
        try {
            if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly();
            if (r != null) r.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }
}
