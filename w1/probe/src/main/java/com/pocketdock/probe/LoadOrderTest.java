package com.pocketdock.probe;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;

/** Kiem tra thu tu nap thu vien goc: A = tao ScreenCapturer truoc, B = tao PeerConnectionFactory truoc. */
public final class LoadOrderTest {
    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "A";
        PeerConnectionFactory factory = null;
        try {
            if (mode.equals("B")) {
                factory = new PeerConnectionFactory();
                System.out.println("B: tao PeerConnectionFactory truoc: OK");
            }
            ScreenCapturer capturer = new ScreenCapturer();
            System.out.println(mode + ": new ScreenCapturer(): OK");
            capturer.dispose();
        } catch (Throwable t) {
            System.out.println(mode + ": LOI - " + t);
        }
        try {
            if (factory != null) factory.dispose();
        } catch (Throwable ignored) {
            // bo qua
        }
        System.exit(0);
    }
}
