package com.pocketdock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Một cuộc nối trực tiếp với một điện thoại.
 * Máy tính là bên tạo đề nghị (offer). Có bốn làn:
 * hình và tiếng từ máy tính sang điện thoại (đang gửi nguồn thử),
 * hình và tiếng từ điện thoại sang máy tính (webcam và micro).
 */
public final class PeerSession implements PeerConnectionObserver {

    public interface Listener {
        /** Có tin cần gửi lên máy chủ (offer hoặc ice). */
        void onSignal(JsonObject message);

        void onPeerState(String text);

        void onChannelState(String text);

        /** Mọi tin nhận qua kênh điều khiển (kể cả nhịp tim). */
        void onControlMessage(String text);

        /** Điện thoại bật hoặc tắt webcam, micro. kind là "webcam" hoặc "mic". */
        void onRemoteMedia(String kind, boolean on, String detail);
    }

    private static final String STREAM_ID = "pocketdock";

    private final JsonArray iceServers;
    private final Listener listener;
    private final Object lock = new Object();
    private final List<JsonObject> queuedLocalIce = new ArrayList<>();
    private final List<RTCIceCandidate> pendingRemoteIce = new ArrayList<>();
    private boolean offerSent;
    private boolean remoteSet;
    private boolean closed;

    private volatile RTCPeerConnection pc;
    private volatile RTCDataChannel control;
    private CustomVideoSource videoSource;
    private CustomVideoSource rxVideoSource;
    private CustomAudioSource audioSource;
    private CustomAudioSource rxAudioSource;
    private VideoTrack videoTrack;
    private VideoTrack rxVideoTrack;
    private AudioTrack audioTrack;
    private AudioTrack rxAudioTrack;
    private volatile TestPatternVideo pattern;
    private volatile BeepAudio beep;

    // Hình và tiếng nhận từ điện thoại
    private volatile VideoTrackSink remoteVideoSink;
    private volatile VideoTrack remoteVideo;
    private volatile AudioTrack remoteAudio;
    private volatile double remoteVolume = 1.0;

    public PeerSession(JsonArray iceServers, Listener listener) {
        this.iceServers = iceServers;
        this.listener = listener;
    }

    /** Nơi nhận các khung hình webcam từ điện thoại. Gọi trước start(). */
    public void setRemoteVideoSink(VideoTrackSink sink) {
        this.remoteVideoSink = sink;
    }

    /** Âm lượng phát tiếng micro điện thoại ra loa máy tính: 0 là tắt tiếng, 1 là nguyên bản. */
    public void setRemoteAudioVolume(double volume) {
        remoteVolume = volume;
        AudioTrack track = remoteAudio;
        if (track != null) {
            try {
                track.setVolume(volume);
            } catch (RuntimeException e) {
                // bỏ qua
            }
        }
    }

    // ---------- khởi tạo ----------

    public void start() {
        PeerConnectionFactory factory = RtcRuntime.factory();

        videoSource = new CustomVideoSource();
        audioSource = new CustomAudioSource();
        rxVideoSource = new CustomVideoSource();
        rxAudioSource = new CustomAudioSource();
        pattern = new TestPatternVideo(videoSource);
        beep = new BeepAudio(audioSource);

        pc = factory.createPeerConnection(buildConfig(), this);

        // Làn 1 và 2: máy tính → điện thoại
        videoTrack = factory.createVideoTrack("screen", videoSource);
        audioTrack = factory.createAudioTrack("system-audio", audioSource);
        addLane(videoTrack, RTCRtpTransceiverDirection.SEND_ONLY, true);
        addLane(audioTrack, RTCRtpTransceiverDirection.SEND_ONLY, true);

        // Làn 3 và 4: điện thoại → máy tính (webcam, micro). Nếu bước này lỗi thì bỏ qua.
        try {
            rxVideoTrack = factory.createVideoTrack("webcam-in", rxVideoSource);
            rxAudioTrack = factory.createAudioTrack("mic-in", rxAudioSource);
            addLane(rxVideoTrack, RTCRtpTransceiverDirection.RECV_ONLY, false);
            addLane(rxAudioTrack, RTCRtpTransceiverDirection.RECV_ONLY, false);
        } catch (RuntimeException e) {
            listener.onPeerState("Không dựng được làn nhận (bỏ qua): " + e.getMessage());
        }

        // Kênh điều khiển phải tạo trước khi tạo offer
        control = pc.createDataChannel("control", new RTCDataChannelInit());
        control.registerObserver(controlObserver);

        createOffer();
    }

    private RTCConfiguration buildConfig() {
        RTCConfiguration config = new RTCConfiguration();
        for (JsonElement element : iceServers) {
            if (!element.isJsonObject()) continue;
            JsonObject o = element.getAsJsonObject();
            JsonElement urls = o.get("urls");
            if (urls == null) continue;

            RTCIceServer server = new RTCIceServer();
            if (urls.isJsonArray()) {
                for (JsonElement u : urls.getAsJsonArray()) server.urls.add(u.getAsString());
            } else {
                server.urls.add(urls.getAsString());
            }
            if (server.urls.isEmpty()) continue;
            if (o.has("username")) server.username = o.get("username").getAsString();
            if (o.has("credential")) server.password = o.get("credential").getAsString();
            config.iceServers.add(server);
        }
        return config;
    }

    private void addLane(MediaStreamTrack track, RTCRtpTransceiverDirection direction, boolean withStream) {
        RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
        init.direction = direction;
        RTCRtpTransceiver transceiver = pc.addTransceiver(track, init);
        if (withStream) {
            transceiver.getSender().setStreams(List.of(STREAM_ID));
        }
    }

    private void createOffer() {
        pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                pc.setLocalDescription(description, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        sendOffer(description.sdp);
                    }

                    @Override
                    public void onFailure(String error) {
                        listener.onPeerState("Lỗi đặt mô tả cục bộ: " + error);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                listener.onPeerState("Lỗi tạo đề nghị kết nối: " + error);
            }
        });
    }

    private void sendOffer(String sdp) {
        JsonObject offer = new JsonObject();
        offer.addProperty("type", "offer");
        offer.addProperty("sdp", sdp);
        listener.onSignal(offer);

        // Các candidate sinh ra trước khi offer được gửi thì gửi sau offer, đúng thứ tự
        List<JsonObject> queued;
        synchronized (lock) {
            offerSent = true;
            queued = new ArrayList<>(queuedLocalIce);
            queuedLocalIce.clear();
        }
        for (JsonObject ice : queued) listener.onSignal(ice);
    }

    // ---------- nhận tin từ điện thoại ----------

    public void handleAnswer(String sdp) {
        RTCPeerConnection p = pc;
        if (p == null) return;
        p.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, sdp),
                new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        List<RTCIceCandidate> toAdd;
                        synchronized (lock) {
                            remoteSet = true;
                            toAdd = new ArrayList<>(pendingRemoteIce);
                            pendingRemoteIce.clear();
                        }
                        for (RTCIceCandidate c : toAdd) addCandidate(c);
                    }

                    @Override
                    public void onFailure(String error) {
                        listener.onPeerState("Lỗi nhận phản hồi: " + error);
                    }
                });
    }

    public void handleRemoteIce(String mid, int index, String sdp) {
        RTCIceCandidate candidate = new RTCIceCandidate(mid == null ? "" : mid, index, sdp, "");
        synchronized (lock) {
            if (closed) return;
            if (!remoteSet) {
                pendingRemoteIce.add(candidate);
                return;
            }
        }
        addCandidate(candidate);
    }

    private void addCandidate(RTCIceCandidate candidate) {
        try {
            RTCPeerConnection p = pc;
            if (p != null) p.addIceCandidate(candidate);
        } catch (RuntimeException e) {
            // một candidate lỗi không làm hỏng cuộc nối
        }
    }

    // ---------- sự kiện từ WebRTC (chạy trên luồng của thư viện) ----------

    @Override
    public void onIceCandidate(RTCIceCandidate candidate) {
        JsonObject ice = new JsonObject();
        ice.addProperty("type", "ice");
        ice.addProperty("candidate", candidate.sdp);
        ice.addProperty("sdpMid", candidate.sdpMid);
        ice.addProperty("sdpMLineIndex", candidate.sdpMLineIndex);
        synchronized (lock) {
            if (closed) return;
            if (!offerSent) {
                queuedLocalIce.add(ice);
                return;
            }
        }
        listener.onSignal(ice);
    }

    @Override
    public void onConnectionChange(RTCPeerConnectionState state) {
        listener.onPeerState("Kết nối trực tiếp: " + describe(state));
        if (state == RTCPeerConnectionState.CONNECTED) {
            startMedia();
        } else if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED) {
            stopMedia();
        }
    }

    /** Được gọi khi điện thoại đồng ý gửi hình hoặc tiếng về làn nhận. */
    @Override
    public void onTrack(RTCRtpTransceiver transceiver) {
        MediaStreamTrack track = transceiver.getReceiver().getTrack();
        synchronized (lock) {
            if (closed) return;
            if (track instanceof VideoTrack) {
                VideoTrack video = (VideoTrack) track;
                remoteVideo = video;
                VideoTrackSink sink = remoteVideoSink;
                if (sink != null) video.addSink(sink);
                EventLog.add("May tinh: lan nhan hinh tu dien thoai san sang");
            } else if (track instanceof AudioTrack) {
                AudioTrack audio = (AudioTrack) track;
                remoteAudio = audio;
                try {
                    audio.setVolume(remoteVolume);
                } catch (RuntimeException e) {
                    // bỏ qua
                }
                EventLog.add("May tinh: lan nhan tieng tu dien thoai san sang");
            }
        }
    }

    private static String describe(RTCPeerConnectionState state) {
        return switch (state) {
            case NEW -> "mới";
            case CONNECTING -> "đang nối...";
            case CONNECTED -> "đã nối";
            case DISCONNECTED -> "mất kết nối";
            case FAILED -> "thất bại";
            case CLOSED -> "đã đóng";
        };
    }

    // ---------- nguồn hình, tiếng thử ----------

    private void startMedia() {
        TestPatternVideo v = pattern;
        BeepAudio a = beep;
        if (v != null) v.start();
        if (a != null) a.start();
    }

    private void stopMedia() {
        TestPatternVideo v = pattern;
        BeepAudio a = beep;
        if (v != null) v.stop();
        if (a != null) a.stop();
    }

    // ---------- kênh điều khiển ----------

    private final RTCDataChannelObserver controlObserver = new RTCDataChannelObserver() {
        @Override
        public void onBufferedAmountChange(long previousAmount) {
        }

        @Override
        public void onStateChange() {
            RTCDataChannel channel = control;
            if (channel == null) return;
            RTCDataChannelState state = channel.getState();
            listener.onChannelState("Kênh điều khiển: "
                    + (state == RTCDataChannelState.OPEN ? "đã mở" : state.name().toLowerCase()));
            if (state == RTCDataChannelState.OPEN) {
                JsonObject hello = new JsonObject();
                hello.addProperty("t", "hello");
                hello.addProperty("from", "desktop");
                sendControl(hello);
            }
        }

        @Override
        public void onMessage(RTCDataChannelBuffer buffer) {
            ByteBuffer copy = buffer.data.duplicate();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            handleControl(new String(bytes, StandardCharsets.UTF_8));
        }
    };

    private void handleControl(String text) {
        listener.onControlMessage(text.length() > 120 ? text.substring(0, 120) : text);
        try {
            JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
            String type = obj.has("t") ? obj.get("t").getAsString() : "";
            switch (type) {
                case "ping" -> {
                    JsonObject pong = new JsonObject();
                    pong.addProperty("t", "pong");
                    if (obj.has("id")) pong.add("id", obj.get("id"));
                    if (obj.has("ts")) pong.add("ts", obj.get("ts"));
                    sendControl(pong);
                }
                case "webcam", "mic" -> {
                    boolean on = obj.has("on") && obj.get("on").getAsBoolean();
                    StringBuilder detail = new StringBuilder();
                    if (obj.has("w") && obj.has("h")) {
                        detail.append(obj.get("w").getAsInt()).append("x").append(obj.get("h").getAsInt());
                    }
                    if (obj.has("fps")) detail.append(" @").append(obj.get("fps").getAsInt());
                    if (obj.has("facing")) detail.append(", camera ").append(obj.get("facing").getAsString());
                    if (obj.has("reason")) detail.append(", lý do: ").append(obj.get("reason").getAsString());
                    listener.onRemoteMedia(type, on, detail.toString().trim());
                }
                default -> { }
            }
        } catch (RuntimeException e) {
            // tin không đúng định dạng, bỏ qua
        }
    }

    public void sendControl(JsonObject message) {
        RTCDataChannel channel = control;
        if (channel == null || channel.getState() != RTCDataChannelState.OPEN) return;
        try {
            byte[] bytes = message.toString().getBytes(StandardCharsets.UTF_8);
            channel.send(new RTCDataChannelBuffer(ByteBuffer.wrap(bytes), false));
        } catch (Exception e) {
            // kênh vừa đóng, bỏ qua
        }
    }

    // ---------- đóng ----------

    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
        }
        stopMedia();
        quietly(() -> {
            VideoTrack video = remoteVideo;
            VideoTrackSink sink = remoteVideoSink;
            if (video != null && sink != null) video.removeSink(sink);
        });
        quietly(() -> {
            RTCDataChannel channel = control;
            if (channel != null) {
                channel.unregisterObserver();
                channel.close();
                channel.dispose();
            }
        });
        quietly(() -> {
            RTCPeerConnection p = pc;
            if (p != null) p.close();
        });
        quietly(() -> { if (videoTrack != null) videoTrack.dispose(); });
        quietly(() -> { if (audioTrack != null) audioTrack.dispose(); });
        quietly(() -> { if (rxVideoTrack != null) rxVideoTrack.dispose(); });
        quietly(() -> { if (rxAudioTrack != null) rxAudioTrack.dispose(); });
        quietly(() -> { if (videoSource != null) videoSource.dispose(); });
        quietly(() -> { if (audioSource != null) audioSource.dispose(); });
        quietly(() -> { if (rxVideoSource != null) rxVideoSource.dispose(); });
        quietly(() -> { if (rxAudioSource != null) rxAudioSource.dispose(); });
    }

    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (Throwable ignored) {
            // dọn dẹp, lỗi lẻ tẻ không quan trọng
        }
    }
}
