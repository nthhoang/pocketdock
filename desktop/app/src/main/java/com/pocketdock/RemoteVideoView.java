package com.pocketdock;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Nhận khung hình từ điện thoại (luồng của WebRTC) và vẽ lên JavaFX. */
public final class RemoteVideoView implements VideoTrackSink {

    // libyuv đặt tên "ARGB" cho thứ tự byte B,G,R,A, khớp với PixelFormat BGRA của JavaFX.
    // Nếu màu bị đảo (da người xanh lè) thì thử đổi sang FourCC.ABGR rồi chạy lại.
    private static final FourCC COLOR_FORMAT = FourCC.ARGB;

    private final ImageView imageView = new ImageView();
    private final Object lock = new Object();
    private byte[] back = new byte[0];   // chỉ luồng nhận hình dùng
    private byte[] front = new byte[0];  // luồng giao diện đọc, giữ khóa
    private int frontWidth;
    private int frontHeight;
    private WritableImage image;
    private final AtomicBoolean drawPending = new AtomicBoolean();
    private final AtomicLong frames = new AtomicLong();
    private volatile int lastWidth;
    private volatile int lastHeight;

    public ImageView getNode() {
        return imageView;
    }

    public long framesTotal() {
        return frames.get();
    }

    public int width() {
        return lastWidth;
    }

    public int height() {
        return lastHeight;
    }

    /** Xóa hình cũ (gọi trên luồng giao diện). */
    public void clear() {
        synchronized (lock) {
            frontWidth = 0;
            frontHeight = 0;
            image = null;
        }
        imageView.setImage(null);
        lastWidth = 0;
        lastHeight = 0;
    }

    @Override
    public void onVideoFrame(VideoFrame frame) {
        try {
            frames.incrementAndGet();
            VideoFrameBuffer buffer = frame.buffer;
            int w = buffer.getWidth();
            int h = buffer.getHeight();
            int size = w * h * 4;
            if (back.length != size) back = new byte[size];

            VideoBufferConverter.convertFromI420(buffer, back, COLOR_FORMAT);

            synchronized (lock) {
                byte[] swap = front;
                front = back;
                back = swap;
                frontWidth = w;
                frontHeight = h;
            }
            lastWidth = w;
            lastHeight = h;

            if (drawPending.compareAndSet(false, true)) {
                Platform.runLater(this::draw);
            }
        } catch (Throwable ignored) {
            // một khung hình lỗi thì bỏ qua
        } finally {
            // QUAN TRỌNG: khung hình nhận từ WebRTC nằm trong bộ nhớ gốc của thư viện (không phải
            // bộ nhớ của Java), nên bộ dọn rác của Java không dọn được. Dùng xong phải báo giải phóng,
            // nếu không mỗi khung hình (khoảng 1,4 MB ở 720p) sẽ bị giữ lại mãi và RAM đầy sau vài phút.
            // Dữ liệu đã được sao sang mảng của mình ở trên nên giải phóng ở đây là an toàn.
            try {
                frame.release();
            } catch (Throwable ignored) {
                // bỏ qua
            }
        }
    }

    private void draw() {
        drawPending.set(false);
        synchronized (lock) {
            if (frontWidth == 0 || front.length < frontWidth * frontHeight * 4) return;
            if (image == null || (int) image.getWidth() != frontWidth || (int) image.getHeight() != frontHeight) {
                image = new WritableImage(frontWidth, frontHeight);
                imageView.setImage(image);
            }
            image.getPixelWriter().setPixels(0, 0, frontWidth, frontHeight,
                    PixelFormat.getByteBgraInstance(), front, 0, frontWidth * 4);
        }
    }
}
