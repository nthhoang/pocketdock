package com.pocketdock;

import dev.onvoid.webrtc.PeerConnectionFactory;

/** Giữ một PeerConnectionFactory dùng chung cho cả ứng dụng. */
public final class RtcRuntime {
    private static PeerConnectionFactory factory;

    private RtcRuntime() {}

    public static synchronized PeerConnectionFactory factory() {
        if (factory == null) {
            factory = new PeerConnectionFactory();
        }
        return factory;
    }

    public static synchronized void shutdown() {
        if (factory != null) {
            try {
                factory.dispose();
            } catch (Throwable ignored) {
                // đang thoát, bỏ qua
            }
            factory = null;
        }
    }
}
