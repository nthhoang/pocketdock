package com.pocketdock;

import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Ba mục kiểm tra chỉ có ý nghĩa trên Windows. SystemCheck.windowsParts() gọi vào đây khi chạy trên Windows. */
final class WindowsChecks {

    static final String NAME_SCREEN = "Chụp màn hình thật và điều khiển chuột";
    static final String NAME_AUDIO = "Âm thanh máy tính (WASAPI) và VB-Cable";
    static final String NAME_OBS = "Webcam ảo (OBS Virtual Camera)";

    private WindowsChecks() {}

    static List<SystemCheck.Result> all() {
        List<SystemCheck.Result> out = new ArrayList<>();
        out.add(safe(NAME_SCREEN, WindowsChecks::screenAndMouse));
        out.add(safe(NAME_AUDIO, WindowsChecks::audioAndCable));
        out.add(safe(NAME_OBS, WindowsChecks::obsCamera));
        return out;
    }

    private static SystemCheck.Result safe(String name, Supplier<SystemCheck.Result> check) {
        try {
            return check.get();
        } catch (Throwable t) {
            return new SystemCheck.Result(name, SystemCheck.Status.FAIL, "Không kiểm tra được: " + t, "");
        }
    }

    // ---------- chụp màn hình và chuột ----------

    static SystemCheck.Result screenAndMouse() {
        RtcRuntime.factory(); // phải có bộ nhà máy WebRTC trước khi dùng ScreenCapturer
        List<DesktopSource> screens;
        ScreenCapturer capturer = new ScreenCapturer();
        try {
            screens = capturer.getDesktopSources();
        } finally {
            capturer.dispose();
        }
        if (screens.isEmpty()) {
            return new SystemCheck.Result(NAME_SCREEN, SystemCheck.Status.FAIL, "Không tìm thấy màn hình nào để chụp",
                    "Kiểm tra máy có đang đăng nhập và không ở màn hình khóa.");
        }
        if (GraphicsEnvironment.isHeadless()) {
            return new SystemCheck.Result(NAME_SCREEN, SystemCheck.Status.FAIL,
                    "Chụp được " + screens.size() + " màn hình nhưng môi trường không có giao diện đồ họa nên Robot không chạy được", "");
        }
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle b = gc.getBounds();
        long percent = Math.round(gc.getDefaultTransform().getScaleX() * 100);
        try {
            new Robot(); // chỉ thử tạo được hay không
        } catch (Exception e) {
            return new SystemCheck.Result(NAME_SCREEN, SystemCheck.Status.FAIL, "Không tạo được Robot điều khiển chuột: " + e, "");
        }
        String detail = screens.size() + " màn hình, chụp được \"" + screens.get(0).title + "\"; vùng chuột (AWT) "
                + b.width + "x" + b.height + ", phóng " + percent + "%";
        return new SystemCheck.Result(NAME_SCREEN, SystemCheck.Status.OK, detail, "");
    }

    // ---------- âm thanh máy tính và VB-Cable ----------

    static SystemCheck.Result audioAndCable() {
        Path exe = LoopbackAudio.firstExisting(LoopbackAudio.candidates());
        if (exe == null) {
            return new SystemCheck.Result(NAME_AUDIO, SystemCheck.Status.FAIL,
                    "Không thấy " + LoopbackAudio.HELPER_EXE,
                    "Dùng bản đóng gói (thư mục audio-helper nằm cạnh PocketDock.exe) hoặc đặt biến POCKETDOCK_AUDIO_HELPER.");
        }
        String probeOutput = runProbe(exe);
        String verdict = probeVerdict(probeOutput);
        if (verdict == null) {
            return new SystemCheck.Result(NAME_AUDIO, SystemCheck.Status.FAIL,
                    "Chương trình phụ không lấy được âm thanh máy: " + probeOutput,
                    "Kiểm tra máy có thiết bị phát âm thanh mặc định đang bật.");
        }
        RtcRuntime.factory();
        List<String> names = playoutNames();
        int index = MicRouting.choose(names, MicRouting.wanted());
        if (index < 0) {
            return new SystemCheck.Result(NAME_AUDIO, SystemCheck.Status.WARN,
                    "Lấy được âm thanh máy (" + verdict + "); chưa thấy thiết bị phát chứa \"" + MicRouting.wanted() + "\"",
                    "Cài VB-Cable (vb-audio.com/Cable) rồi khởi động lại máy. Chưa có thì tiếng micro điện thoại phát ra loa mặc định.");
        }
        return new SystemCheck.Result(NAME_AUDIO, SystemCheck.Status.OK,
                "Lấy được âm thanh máy (" + verdict + "); tiếng micro điện thoại đi vào: " + names.get(index), "");
    }

    /** Chạy chương trình phụ với --probe và trả về dòng nó in ra. Lỗi thì trả về chữ "LOI ...". */
    static String runProbe(Path exe) {
        try {
            Process p = new ProcessBuilder(exe.toString(), "--probe").redirectErrorStream(true).start();
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "LOI het thoi gian cho (8 giay)";
            }
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "LOI bi ngat";
        } catch (Exception e) {
            return "LOI khong chay duoc chuong trinh phu: " + e.getMessage();
        }
    }

    /** Chương trình phụ in "OK thiet bi=...; dinh dang=..." khi mở được thiết bị. Trả về phần sau chữ OK, hoặc null nếu lỗi. */
    static String probeVerdict(String output) {
        if (output == null) return null;
        String t = output.trim();
        if (t.equals("OK") || t.startsWith("OK ")) return t.substring(2).trim();
        return null;
    }

    private static List<String> playoutNames() {
        List<String> names = new ArrayList<>();
        for (AudioDevice d : MediaDevices.getAudioRenderDevices()) names.add(d.getName());
        return names;
    }

    // ---------- OBS Virtual Camera ----------

    static SystemCheck.Result obsCamera() {
        RtcRuntime.factory();
        List<String> cameras = new ArrayList<>();
        for (VideoDevice d : MediaDevices.getVideoCaptureDevices()) cameras.add(d.getName());
        String found = obsListed(cameras);
        if (found != null) {
            return new SystemCheck.Result(NAME_OBS, SystemCheck.Status.OK, "Thấy thiết bị: " + found, "");
        }
        if (obsInstalled()) {
            return new SystemCheck.Result(NAME_OBS, SystemCheck.Status.WARN,
                    "OBS Studio đã cài nhưng chưa thấy OBS Virtual Camera trong danh sách camera: " + cameras,
                    "Mở OBS Studio, bấm Start Virtual Camera một lần rồi chạy lại kiểm tra.");
        }
        return new SystemCheck.Result(NAME_OBS, SystemCheck.Status.WARN, "Chưa cài OBS Studio",
                "Cài OBS Studio (obsproject.com) nếu muốn đưa webcam điện thoại vào Zoom hoặc Teams.");
    }

    /** Trả về tên camera OBS trong danh sách, hoặc null nếu không có. */
    static String obsListed(List<String> cameraNames) {
        if (cameraNames == null) return null;
        for (String n : cameraNames) {
            if (n != null && n.toLowerCase(Locale.ROOT).contains("obs virtual camera")) return n;
        }
        return null;
    }

    private static boolean obsInstalled() {
        for (String var : new String[] {"ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"}) {
            String base = System.getenv(var);
            if (base == null || base.isBlank()) continue;
            if (Files.exists(Path.of(base, "obs-studio", "bin", "64bit", "obs64.exe"))) return true;
        }
        return false;
    }
}
