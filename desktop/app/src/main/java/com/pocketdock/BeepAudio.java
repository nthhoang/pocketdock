package com.pocketdock;

import dev.onvoid.webrtc.media.audio.CustomAudioSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Tiếng bíp 880 Hz dài 0,2 giây, lặp mỗi giây. Âm thanh 16-bit, 48 kHz, 2 kênh, đẩy từng khúc 10 ms. */
public final class BeepAudio {
    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    private static final int FRAMES = 480; // 10 ms ở 48 kHz
    private static final double FREQUENCY = 880.0;

    private final CustomAudioSource source;
    private final byte[] buffer = new byte[FRAMES * CHANNELS * 2];
    private ScheduledExecutorService executor;
    private long sampleIndex;
    private boolean errorLogged;

    public BeepAudio(CustomAudioSource source) {
        this.source = source;
    }

    public synchronized void start() {
        if (executor != null) return;
        // Âm thanh phải được đẩy từ đúng một luồng
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-audio");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::pushChunk, 0, 10, TimeUnit.MILLISECONDS);
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

    private void pushChunk() {
        try {
            ByteBuffer bytes = ByteBuffer.wrap(buffer).order(ByteOrder.nativeOrder());
            for (int i = 0; i < FRAMES; i++) {
                long n = sampleIndex + i;
                double t = (n % SAMPLE_RATE) / (double) SAMPLE_RATE; // vị trí trong giây hiện tại
                double envelope = 0.0;
                if (t < 0.20) {
                    // Lên xuống êm 10 ms ở hai đầu để không bị tiếng lụp bụp
                    envelope = Math.min(1.0, Math.min(t / 0.01, (0.20 - t) / 0.01));
                }
                double angle = 2.0 * Math.PI * FREQUENCY * (n % SAMPLE_RATE) / SAMPLE_RATE;
                short sample = (short) (Math.sin(angle) * envelope * 0.25 * Short.MAX_VALUE);
                bytes.putShort(sample);
                bytes.putShort(sample);
            }
            sampleIndex += FRAMES;
            source.pushAudio(buffer, 16, SAMPLE_RATE, CHANNELS, FRAMES);
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                System.err.println("Lỗi tạo tiếng bíp: " + t);
            }
        }
    }
}
