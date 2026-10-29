package com.pocketdock.probe;

import com.sun.management.OperatingSystemMXBean;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.MediaStreamTrack;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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

    /** Do CPU cua chinh chuong trinh nay, lay mau moi 0,5 giay. */
    private static final class CpuSampler {
        private final OperatingSystemMXBean os =
                (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        private double sum;
        private int count;

        void sample() {
            double load = os.getProcessCpuLoad();
            if (load >= 0) {
                sum += load;
                count++;
            }
        }

        double averagePercent() {
            return count > 0 ? sum / count * 100 : -1;
        }
    }

    private static void run(Report r) {
        r.line("=== THU CHUP MAN HINH (webrtc-java) ===");
        r.line("He dieu hanh: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        r.line("Java: " + System.getProperty("java.version") + ", so luong CPU: "
                + Runtime.getRuntime().availableProcessors());

        PeerConnectionFactory factory = null;
        ScreenCapturer capturer = null;
        try {
            // Buoc 0: phai khoi tao WebRTC truoc, viec nay nap thu vien goc
            r.line("");
            r.line("--- Buoc 0: khoi tao WebRTC ---");
            try {
                factory = new PeerConnectionFactory();
                r.line("PeerConnectionFactory: OK");
                smokeTest(r, factory);
            } catch (Throwable t) {
                r.line("KET QUA: LOI - khong khoi tao duoc WebRTC: " + t);
                r.trace(t);
                return;
            }

            // Buoc 1: liet ke man hinh
            r.line("");
            r.line("--- Buoc 1: liet ke man hinh ---");
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
            long id = screens.get(0).id;
            r.line("Se chup man hinh dau tien: " + screens.get(0).title);

            // Buoc 2: chi chup man hinh
            int[][] captureConfigs = {{1920, 1080, 30}, {1280, 720, 30}};
            for (int[] c : captureConfigs) {
                runCapture(r, factory, id, c[0], c[1], c[2], 8);
            }

            // Buoc 3: chup + ma hoa VP8 + nhan lai trong cung may (gan voi cach app that se chay)
            int[][] encodeConfigs = {{1280, 720, 30}, {1920, 1080, 30}};
            for (int[] c : encodeConfigs) {
                encodeLoopback(r, factory, id, c[0], c[1], c[2], 10);
            }
        } catch (Throwable t) {
            r.line("KET QUA: LOI - " + t);
            r.trace(t);
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

    /** Tao thu mot peer connection, mot kenh du lieu va mot offer. */
    private static void smokeTest(Report r, PeerConnectionFactory factory) throws Exception {
        RTCPeerConnection pc = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });
        RTCDataChannel channel = pc.createDataChannel("probe", new RTCDataChannelInit());
        CountDownLatch latch = new CountDownLatch(1);
        String[] result = new String[1];
        pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                result[0] = "OK, SDP dai " + description.sdp.length() + " ky tu";
                latch.countDown();
            }

            @Override
            public void onFailure(String error) {
                result[0] = "LOI: " + error;
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        r.line("Tao peer connection, kenh du lieu va offer: " + (result[0] == null ? "QUA THOI GIAN" : result[0]));
        channel.dispose();
        pc.close();
    }

    // ---------- chi chup man hinh ----------

    private static void runCapture(Report r, PeerConnectionFactory factory, long sourceId,
                                   int maxW, int maxH, int fps, int seconds) {
        r.line("");
        r.line("--- Buoc 2: chi chup, toi da " + maxW + "x" + maxH + " @ " + fps + " khung/giay, "
                + seconds + " giay ---");

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

            CpuSampler cpu = new CpuSampler();
            long startNs = System.nanoTime();
            source.start();
            long endNs = startNs + seconds * 1_000_000_000L;
            while (System.nanoTime() < endNs) {
                Thread.sleep(500);
                cpu.sample();
            }
            long elapsedNs = System.nanoTime() - startNs;
            source.stop();
            track.removeSink(sink);

            long n = frames.get();
            double fpsMeasured = n / (elapsedNs / 1e9);
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
            double cpuPercent = cpu.averagePercent();
            r.line("CPU trung binh cua chuong trinh: "
                    + (cpuPercent >= 0 ? f(cpuPercent) + " %" : "khong do duoc") + " (tren tong so luong CPU)");

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
            r.trace(t);
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

    // ---------- chup + ma hoa + nhan lai trong cung may ----------

    private static SetSessionDescriptionObserver setObserver(Runnable ok, Consumer<String> fail) {
        return new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                ok.run();
            }

            @Override
            public void onFailure(String error) {
                fail.accept(error);
            }
        };
    }

    private static CreateSessionDescriptionObserver createObserver(Consumer<RTCSessionDescription> ok,
                                                                   Consumer<String> fail) {
        return new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                ok.accept(description);
            }

            @Override
            public void onFailure(String error) {
                fail.accept(error);
            }
        };
    }

    private static void negotiate(RTCPeerConnection pa, RTCPeerConnection pb, Runnable done, Consumer<String> fail) {
        pa.createOffer(new RTCOfferOptions(), createObserver(offer -> {
            pa.setLocalDescription(offer, setObserver(() -> {
                pb.setRemoteDescription(offer, setObserver(() -> {
                    pb.createAnswer(new RTCAnswerOptions(), createObserver(answer -> {
                        pb.setLocalDescription(answer, setObserver(() -> {
                            pa.setRemoteDescription(answer, setObserver(done, fail));
                        }, fail));
                    }, fail));
                }, fail));
            }, fail));
        }, fail));
    }

    private static void relay(RTCIceCandidate candidate, AtomicBoolean negotiated,
                              List<RTCIceCandidate> pending, RTCPeerConnection[] pcs, int targetIndex) {
        synchronized (pending) {
            if (!negotiated.get()) {
                pending.add(candidate);
                return;
            }
        }
        try {
            pcs[targetIndex].addIceCandidate(candidate);
        } catch (Throwable ignored) {
            // mot candidate loi khong lam hong thu nghiem
        }
    }

    private static void flush(List<RTCIceCandidate> pending, RTCPeerConnection target) {
        synchronized (pending) {
            for (RTCIceCandidate c : pending) {
                try {
                    target.addIceCandidate(c);
                } catch (Throwable ignored) {
                    // bo qua
                }
            }
            pending.clear();
        }
    }

    private static void encodeLoopback(Report r, PeerConnectionFactory factory, long sourceId,
                                       int maxW, int maxH, int fps, int seconds) {
        r.line("");
        r.line("--- Buoc 3: chup + ma hoa VP8 + giai ma noi bo, toi da " + maxW + "x" + maxH
                + " @ " + fps + ", " + seconds + " giay ---");

        VideoDesktopSource source = null;
        VideoTrack sendTrack = null;
        RTCPeerConnection a = null;
        RTCPeerConnection b = null;
        try {
            source = new VideoDesktopSource();
            source.setFrameRate(fps);
            source.setMaxFrameSize(maxW, maxH);
            source.setSourceId(sourceId, false);
            sendTrack = factory.createVideoTrack("loop-send", source);

            AtomicLong received = new AtomicLong();
            AtomicInteger recvW = new AtomicInteger();
            AtomicInteger recvH = new AtomicInteger();
            AtomicBoolean negotiated = new AtomicBoolean();
            List<RTCIceCandidate> fromA = new ArrayList<>();
            List<RTCIceCandidate> fromB = new ArrayList<>();
            CountDownLatch connected = new CountDownLatch(1);
            CountDownLatch negotiationDone = new CountDownLatch(1);
            String[] error = new String[1];
            RTCPeerConnection[] pcs = new RTCPeerConnection[2];

            a = factory.createPeerConnection(new RTCConfiguration(), new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    relay(candidate, negotiated, fromA, pcs, 1);
                }

                @Override
                public void onConnectionChange(RTCPeerConnectionState state) {
                    if (state == RTCPeerConnectionState.CONNECTED) connected.countDown();
                }
            });
            b = factory.createPeerConnection(new RTCConfiguration(), new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    relay(candidate, negotiated, fromB, pcs, 0);
                }

                @Override
                public void onTrack(RTCRtpTransceiver transceiver) {
                    MediaStreamTrack track = transceiver.getReceiver().getTrack();
                    if (track instanceof VideoTrack) {
                        ((VideoTrack) track).addSink(frame -> {
                            try {
                                received.incrementAndGet();
                                VideoFrameBuffer buffer = frame.buffer;
                                recvW.set(buffer.getWidth());
                                recvH.set(buffer.getHeight());
                            } catch (Throwable ignored) {
                                // bo qua
                            }
                        });
                    }
                }
            });
            pcs[0] = a;
            pcs[1] = b;

            RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
            init.direction = RTCRtpTransceiverDirection.SEND_ONLY;
            a.addTransceiver(sendTrack, init);

            Consumer<String> fail = e -> {
                error[0] = e;
                negotiationDone.countDown();
            };
            negotiate(a, b, () -> {
                negotiated.set(true);
                flush(fromA, pcs[1]);
                flush(fromB, pcs[0]);
                negotiationDone.countDown();
            }, fail);

            source.start();
            boolean negotiatedInTime = negotiationDone.await(10, TimeUnit.SECONDS);
            if (!negotiatedInTime || error[0] != null) {
                r.line("KET QUA: LOI - thuong luong ket noi that bai: "
                        + (error[0] != null ? error[0] : "qua thoi gian"));
                return;
            }
            if (!connected.await(10, TimeUnit.SECONDS)) {
                r.line("KET QUA: LOI - hai dau khong ket noi duoc trong 10 giay.");
                return;
            }

            long startFrames = received.get();
            CpuSampler cpu = new CpuSampler();
            long startNs = System.nanoTime();
            long endNs = startNs + seconds * 1_000_000_000L;
            while (System.nanoTime() < endNs) {
                Thread.sleep(500);
                cpu.sample();
            }
            long elapsedNs = System.nanoTime() - startNs;
            long n = received.get() - startFrames;
            double fpsMeasured = n / (elapsedNs / 1e9);

            r.line("So khung hinh nhan lai duoc: " + n + " (" + f(fpsMeasured) + " khung/giay)");
            r.line("Kich thuoc khung hinh nhan lai: " + recvW.get() + "x" + recvH.get());
            double cpuPercent = cpu.averagePercent();
            r.line("CPU trung binh: " + (cpuPercent >= 0 ? f(cpuPercent) + " %" : "khong do duoc")
                    + " (gom ca ma hoa va giai ma trong cung chuong trinh, nen la muc TREN; app that chi ma hoa)");
            if (n == 0) {
                r.line("KET QUA: LOI - khong nhan lai khung hinh nao.");
            } else if (fpsMeasured < 15) {
                r.line("KET QUA: CHAM - duoi 15 khung/giay.");
            } else {
                r.line("KET QUA: OK");
            }
        } catch (Throwable t) {
            r.line("KET QUA: LOI - " + t);
            r.trace(t);
        } finally {
            try {
                if (source != null) source.stop();
            } catch (Throwable ignored) {
                // bo qua
            }
            try {
                if (a != null) a.close();
            } catch (Throwable ignored) {
                // bo qua
            }
            try {
                if (b != null) b.close();
            } catch (Throwable ignored) {
                // bo qua
            }
            try {
                if (sendTrack != null) sendTrack.dispose();
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

    // ---------- phuong an du phong ----------

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
