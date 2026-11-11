package com.pocketdock;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;

import java.util.ArrayList;
import java.util.List;

/** Giữ một PeerConnectionFactory dùng chung cho cả ứng dụng. */
public final class RtcRuntime {
    private static PeerConnectionFactory factory;
    private static AudioDeviceModule audioModule;
    private static String micRoute = "Chưa khởi tạo";

    private RtcRuntime() {}

    public static synchronized PeerConnectionFactory factory() {
        if (factory == null) {
            factory = createFactory();
        }
        return factory;
    }

    /** Mô tả nơi đang phát tiếng micro từ điện thoại (dùng cho màn hình Kiểm tra hệ thống). */
    public static synchronized String micRoute() {
        return micRoute;
    }

    /**
     * Trên Windows: tạo bộ âm thanh riêng và đặt thiết bị phát là VB-Cable (CABLE Input),
     * để tiếng micro từ điện thoại đi vào ứng dụng họp. Không thấy VB-Cable hoặc lỗi thì dùng loa mặc định.
     * Hệ điều hành khác: giữ cách cũ.
     */
    private static PeerConnectionFactory createFactory() {
        if (!MicRouting.enabled()) {
            micRoute = "Loa mặc định (không bật đưa tiếng vào VB-Cable)";
            return new PeerConnectionFactory();
        }
        AudioDeviceModule module = null;
        try {
            module = new AudioDeviceModule();
            List<AudioDevice> speakers = module.getPlayoutDevices();
            List<String> names = new ArrayList<>();
            for (AudioDevice d : speakers) names.add(d.getName());
            EventLog.add("Micro: cac thiet bi phat: " + names);
            String wanted = MicRouting.wanted();
            int index = MicRouting.choose(names, wanted);
            if (index >= 0) {
                module.setPlayoutDevice(speakers.get(index));
                module.initPlayout();
                micRoute = "Đưa tiếng micro điện thoại vào: " + names.get(index);
                EventLog.add("Micro: dua tieng micro dien thoai vao " + names.get(index));
            } else {
                micRoute = "Không thấy thiết bị \"" + wanted + "\", tiếng micro phát ra loa mặc định";
                EventLog.add("Micro: khong thay thiet bi phat chua \"" + wanted + "\", dung loa mac dinh");
            }
            PeerConnectionFactory created = new PeerConnectionFactory(module);
            audioModule = module;
            return created;
        } catch (Throwable t) {
            micRoute = "Loa mặc định (không chọn được thiết bị phát)";
            EventLog.add("Micro: khong chon duoc thiet bi phat (" + t + "), dung loa mac dinh");
            if (module != null) {
                try {
                    module.dispose();
                } catch (Throwable ignored) {
                    // bỏ qua
                }
            }
            return new PeerConnectionFactory();
        }
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
        if (audioModule != null) {
            try {
                audioModule.dispose();
            } catch (Throwable ignored) {
                // đang thoát, bỏ qua
            }
            audioModule = null;
        }
    }
}
