package com.pocketdock;

import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Hình thử: 8 vạch màu và một dải trắng chạy ngang, 640x360, 30 khung hình mỗi giây. */
public final class TestPatternVideo {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 360;
    private static final int FPS = 30;
    private static final int STRIPE = 16; // số chẵn

    // Y, U, V của 8 vạch màu: trắng, vàng, lam nhạt, lục, tím, đỏ, lam, đen
    private static final int[][] BARS = {
            {235, 128, 128}, {210, 16, 146}, {170, 166, 16}, {145, 54, 34},
            {106, 202, 222}, {81, 90, 240}, {41, 240, 110}, {16, 128, 128}
    };

    private final CustomVideoSource source;
    private final byte[] rowY = new byte[WIDTH];
    private final byte[] rowU = new byte[WIDTH / 2];
    private final byte[] rowV = new byte[WIDTH / 2];
    private ScheduledExecutorService executor;
    private long frameIndex;
    private boolean errorLogged;

    public TestPatternVideo(CustomVideoSource source) {
        this.source = source;
        int barWidth = WIDTH / BARS.length;
        for (int x = 0; x < WIDTH; x++) {
            int[] c = BARS[Math.min(x / barWidth, BARS.length - 1)];
            rowY[x] = (byte) c[0];
            if (x % 2 == 0) {
                rowU[x / 2] = (byte) c[1];
                rowV[x / 2] = (byte) c[2];
            }
        }
    }

    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-video");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::pushFrame, 0, 1_000_000_000L / FPS, TimeUnit.NANOSECONDS);
    }

    public synchronized void stop() {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    private void pushFrame() {
        try {
            NativeI420Buffer buffer = NativeI420Buffer.allocate(WIDTH, HEIGHT);
            ByteBuffer y = buffer.getDataY();
            ByteBuffer u = buffer.getDataU();
            ByteBuffer v = buffer.getDataV();
            int strideY = buffer.getStrideY();
            int strideU = buffer.getStrideU();
            int strideV = buffer.getStrideV();

            for (int row = 0; row < HEIGHT; row++) {
                y.put(row * strideY, rowY, 0, WIDTH);
            }
            for (int row = 0; row < HEIGHT / 2; row++) {
                u.put(row * strideU, rowU, 0, WIDTH / 2);
                v.put(row * strideV, rowV, 0, WIDTH / 2);
            }

            // Dải trắng chạy qua lại, 60 khung hình một lượt
            int stripeX = (int) ((frameIndex % 60) * (WIDTH - STRIPE) / 59);
            stripeX &= ~1;
            for (int row = 0; row < HEIGHT; row++) {
                for (int x = stripeX; x < stripeX + STRIPE; x++) {
                    y.put(row * strideY + x, (byte) 235);
                }
            }
            for (int row = 0; row < HEIGHT / 2; row++) {
                for (int x = stripeX / 2; x < (stripeX + STRIPE) / 2; x++) {
                    u.put(row * strideU + x, (byte) 128);
                    v.put(row * strideV + x, (byte) 128);
                }
            }

            VideoFrame frame = new VideoFrame(buffer, System.nanoTime());
            try {
                source.pushFrame(frame);
            } finally {
                frame.release();
            }
            frameIndex++;
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                System.err.println("Lỗi tạo hình thử: " + t);
            }
        }
    }
}
