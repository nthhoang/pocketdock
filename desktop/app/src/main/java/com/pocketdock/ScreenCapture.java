package com.pocketdock;

import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;

import java.util.List;

/**
 * Chụp màn hình thật bằng VideoDesktopSource của webrtc-java (cách gọi lấy từ bộ thử W1).
 * Mặc định chỉ bật trên Windows. Biến môi trường POCKETDOCK_SCREEN: "real" ép dùng, "test" ép dùng hình thử.
 * Nếu không tạo được thì tryCreate trả về null và ứng dụng dùng hình thử như cũ.
 */
public final class ScreenCapture {

    private final VideoDesktopSource source;
    private final ScreenQuality quality;
    private boolean running;

    private ScreenCapture(VideoDesktopSource source, ScreenQuality quality) {
        this.source = source;
        this.quality = quality;
    }

    /** Có nên thử chụp màn hình thật không (chỉ quyết định theo hệ điều hành và biến môi trường). */
    public static boolean enabled() {
        String mode = System.getenv("POCKETDOCK_SCREEN");
        if ("test".equalsIgnoreCase(mode)) return false;
        if ("real".equalsIgnoreCase(mode)) return true;
        return SystemCheck.isWindows();
    }

    /** Phải gọi sau khi đã tạo PeerConnectionFactory (RtcRuntime.factory()), nếu không bị UnsatisfiedLinkError. */
    public static ScreenCapture tryCreate(ScreenQuality quality) {
        if (!enabled()) return null;
        ScreenCapturer capturer = null;
        VideoDesktopSource source = null;
        try {
            capturer = new ScreenCapturer();
            List<DesktopSource> screens = capturer.getDesktopSources();
            if (screens.isEmpty()) {
                EventLog.add("Nguon hinh: khong tim thay man hinh de chup, dung hinh thu");
                return null;
            }
            DesktopSource first = screens.get(0);
            source = new VideoDesktopSource();
            source.setFrameRate(quality.fps());
            source.setMaxFrameSize(quality.width(), quality.height());
            source.setSourceId(first.id, false);
            EventLog.add("Nguon hinh: chup man hinh that \"" + first.title + "\" toi da " + quality.describe()
                    + " (" + quality.label() + ", " + screens.size() + " man hinh)");
            return new ScreenCapture(source, quality);
        } catch (Throwable t) {
            EventLog.add("Nguon hinh: khong chup duoc man hinh (" + t + "), dung hinh thu");
            if (source != null) {
                try {
                    source.dispose();
                } catch (Throwable ignored) {
                    // bo qua
                }
            }
            return null;
        } finally {
            if (capturer != null) {
                try {
                    capturer.dispose();
                } catch (Throwable ignored) {
                    // bo qua
                }
            }
        }
    }

    public VideoDesktopSource source() {
        return source;
    }

    public synchronized void start() {
        if (running) return;
        try {
            source.start();
            running = true;
            EventLog.add("Nguon hinh: bat dau chup (" + quality.label() + ")");
        } catch (Throwable t) {
            EventLog.add("Nguon hinh: loi khi bat dau chup: " + t);
        }
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try {
            source.stop();
            EventLog.add("Nguon hinh: dung chup");
        } catch (Throwable t) {
            EventLog.add("Nguon hinh: loi khi dung chup: " + t);
        }
    }

    public synchronized void dispose() {
        stop();
        try {
            source.dispose();
        } catch (Throwable ignored) {
            // bo qua
        }
    }
}
