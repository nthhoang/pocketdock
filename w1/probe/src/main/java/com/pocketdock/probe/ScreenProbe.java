package com.pocketdock.probe;

import com.sun.management.OperatingSystemMXBean;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.video.I420Buffer;
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ScreenProbe {

    public static void main(String[] args) throws Exception {
        try (Report r = new Report("ketqua-man-hinh.txt")) {
            run(r);
            awtBaseline(r);
        }
        System.exit(0);
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static void run(Report r) {
        r.line("=== THU CHUP MAN HINH (webrtc-java) ===");
        r.line("He dieu hanh: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        r.line("Java: " + System.getProperty("java.version") + ", so luong CPU: "
                + Runtime.getRuntime().availableProcessors());

        ScreenCapturer capturer = null;
        PeerConnectionFactory factory = null;
        try {
            capturer = new ScreenCapturer();
            List<DesktopSource> screens = capturer.getDesktopSources();
            r.line("So man hinh tim thay: " + screens.size());
            for (DesktopSource s : screens) {
                r.line("  - " + s.title + " (id " + s.id + ")");
            }
            if (screens.isEmpty()) {
                r.line("KET QUA: LOI - khong co man hinh nao de chup.");
                return;
            }

            factory = new PeerConnectionFactory();
            long id = screens.get(0).id;
            r.line("Se chup man hinh dau tien: " + screens.get(0).title);

            int[][] configs = {{1920, 1080, 30}, {1280, 720, 30}};
            for (int[] c : configs) {
                runCapture(r, factory, id, c[0], c[1], c[2], 8);
            }
        } catch (Throwable t) {
            r.line("KET QUA: LOI - " + t);
            t.printStackTrace();
        } finally {
            try {
                if (capturer != null) capturer.dispose();
            } catch (Throwable ignored) {
                // bo qua
            }
            try {
                if (factory != null) factory.dispose();
            } catch (Throwable ignored) {
                // bo qua
            }
        }
    }

    private static void runCapture(Report r, PeerConnectionFactory factory, long sourceId,
                                   int maxW, int maxH, int fps, int seconds) {
        r.line("");
        r.line("--- Thu toi da " + maxW + "x" + maxH + " @ " + fps + " khung/giay, " + seconds + " giay ---");

        VideoDesktopSource source = null;
        VideoTrack track = null;
        VideoTrackSink sink = null;
        try {
            source = new VideoDesktopSource();
            source.setFrameRate(fps);
            source.setMaxFrameSize(maxW, maxH);
            source.setSourceId(sourceId, false);
            track = factory.createVideoTrack("probe", source);

            AtomicLong frames = new AtomicLong();
            AtomicLong firstNs = new AtomicLong();
            AtomicInteger width = new AtomicInteger();
            AtomicInteger height = new AtomicInteger();
            long[] luma = {0, 0, 255, 0}; // tong, so mau, nho nhat, lon nhat

            sink = frame -> {
                try {
                    long n = frames.incrementAndGet();
                    if (n == 1) firstNs.set(System.nanoTime());
                    VideoFrameBuffer buffer = frame.buffer;
                    width.set(buffer.getWidth());
                    height.set(buffer.getHeight());
                    if (n % 10 == 1 && buffer instanceof I420Buffer) {
                        sampleLuma((I420Buffer) buffer, luma);
                    }
                } catch (Throwable ignored) {
                    // khong de loi trong bo nhan khung hinh
                }
            };
            track.addSink(sink);

            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long startNs = System.nanoTime();
            source.start();

            double cpuSum = 0;
            int cpuN = 0;
            long endNs = startNs + seconds * 1_000_000_000L;
            while (System.nanoTime() < endNs) {
                Thread.sleep(500);
                double load = os.getProcessCpuLoad();
                if (load >= 0) {
                    cpuSum += load;
                    cpuN++;
                }
            }
            long elapsedNs = System.nanoTime() - startNs;
            source.stop();
            track.removeSink(sink);

            long n = frames.get();
            double secs = elapsedNs / 1e9;
            double fpsMeasured = n / secs;
            r.line("So khung hinh nhan duoc: " + n + " (" + f(fpsMeasured) + " khung/giay)");
            if (n > 0) {
                r.line("Thoi gian den khung hinh dau tien: " + f((firstNs.get() - startNs) / 1e6) + " ms");
                r.line("Kich thuoc khung hinh nhan duoc: " + width.get() + "x" + height.get());
            }
            double lumaAvg = -1;
            synchronized (luma) {
                if (luma[1] > 0) {
                    lumaAvg = luma[0] / (double) luma[1];
                    r.line("Do sang pixel (0-255): trung binh " + f(lumaAvg) + ", nho nhat " + luma[2]
                            + ", lon nhat " + luma[3] + " (" + luma[1] + " mau)");
                } else {
                    r.line("Khong lay mau duoc do sang (dinh dang khung hinh khac I420).");
                }
            }
            double cpu = cpuN > 0 ? cpuSum / cpuN * 100 : -1;
            r.line("CPU trung binh cua chuong trinh: " + (cpu >= 0 ? f(cpu) + " %" : "khong do duoc")
                    + " (tinh tren tong so luong CPU)");

            if (n == 0) {
                r.line("KET QUA: LOI - khong nhan duoc khung hinh nao.");
            } else if (fpsMeasured < 15) {
                r.line("KET QUA: CHAM - duoi 15 khung/giay.");
            } else if (lumaAvg >= 0 && lumaAvg < 3 && luma[3] < 10) {
                r.line("KET QUA: CANH BAO - hinh gan nhu toan mau den. Thu lai khi man hinh dang co noi dung sang.");
            } else {
                r.line("KET QUA: OK");
            }
        } catch (Throwable t) {
            r.line("KET QUA: LOI - " + t);
            t.printStackTrace();
        } finally {
            try {
                if (track != null) track.dispose();
            } catch (Throwable ignored) {
                // bo qua
            }
            try {
                if (source != null) source.dispose();
            } catch (Throwable ignored) {
                // bo qua
            }
        }
    }

    private static void sampleLuma(I420Buffer i420, long[] stats) {
        ByteBuffer y = i420.getDataY();
        int stride = i420.getStrideY();
        int w = i420.getWidth();
        int h = i420.getHeight();
        long sum = 0;
        long count = 0;
        int min = 255;
        int max = 0;
        for (int row = 0; row < h; row += 16) {
            for (int x = 0; x < w; x += 16) {
                int v = y.get(row * stride + x) & 0xFF;
                sum += v;
                count++;
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }
        synchronized (stats) {
            stats[0] += sum;
            stats[1] += count;
            stats[2] = Math.min(stats[2], min);
            stats[3] = Math.max(stats[3], max);
        }
    }

    /** Phuong an du phong: chup bang java.awt.Robot, do toc do. */
    private static void awtBaseline(Report r) {
        r.line("");
        r.line("--- Phuong an du phong: chup bang java.awt.Robot ---");
        try {
            Robot robot = new Robot();
            Rectangle area = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            int n = 20;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                robot.createScreenCapture(area);
            }
            double ms = (System.nanoTime() - t0) / 1e6 / n;
            r.line("Vung chup (don vi logic): " + area.width + "x" + area.height);
            r.line("Moi khung hinh mat " + f(ms) + " ms (khoang " + f(1000.0 / ms) + " khung/giay)");
        } catch (Throwable t) {
            r.line("Khong chup duoc bang Robot: " + t);
        }
    }
}
