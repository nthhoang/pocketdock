package com.pocketdock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PocketDockApp extends Application implements SignalingClient.Handler {

    // Địa chỉ máy chủ để app kết nối, và địa chỉ công khai để ghi vào mã QR cho điện thoại mở
    private static final String SERVER = env("POCKETDOCK_SERVER", "http://localhost:8081");
    private static final String PUBLIC_URL = env("POCKETDOCK_PUBLIC_URL", SERVER);

    private Stage stage;
    private final ImageView qrView = new ImageView();
    private final Label countdownLabel = new Label();
    private final Label statusLabel = new Label();
    private final Label peerLabel = new Label();
    private final Label channelLabel = new Label();
    private final Label messagesLabel = new Label();
    private final Label linkHint = new Label("Không quét được? Sao chép địa chỉ này:");
    private final TextField linkField = new TextField();
    private final Button retryButton = new Button("Kết nối lại");
    private final Button disconnectButton = new Button("Ngắt kết nối thiết bị");

    private volatile SignalingClient client;
    private Timeline countdown;
    private int secondsLeft;
    private Alert pendingDialog;
    private boolean deviceGone;

    private JsonArray iceServers = new JsonArray();
    private volatile PeerSession peer;
    private final Deque<String> recentMessages = new ArrayDeque<>();
    // Mọi thao tác WebRTC chạy tuần tự trên một luồng riêng, để không làm đơ giao diện
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "webrtc-worker");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;

        Label title = new Label("PocketDock");
        title.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");

        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(360);
        statusLabel.setStyle("-fx-font-size: 15px;");
        countdownLabel.setStyle("-fx-text-fill: #666666;");
        linkHint.setStyle("-fx-text-fill: #666666; -fx-font-size: 12px;");
        for (Label label : new Label[]{peerLabel, channelLabel, messagesLabel}) {
            label.setWrapText(true);
            label.setMaxWidth(360);
            label.setStyle("-fx-font-size: 13px;");
        }

        qrView.setFitWidth(280);
        qrView.setFitHeight(280);
        qrView.setSmooth(false);

        linkField.setEditable(false);
        linkField.setMaxWidth(360);

        retryButton.setOnAction(e -> connect());
        disconnectButton.setOnAction(e -> disconnectDevice());

        VBox root = new VBox(10, title, qrView, countdownLabel, statusLabel,
                peerLabel, channelLabel, messagesLabel,
                linkHint, linkField, retryButton, disconnectButton);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(24));

        stage.setTitle("PocketDock");
        stage.setScene(new Scene(root, 440, 720));
        stage.show();

        connect();
    }

    @Override
    public void stop() {
        closePeer();
        if (client != null) client.close();
        worker.execute(RtcRuntime::shutdown);
        worker.shutdown();
        try {
            worker.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- kết nối máy chủ ----------

    private void connect() {
        closePeer();
        if (client != null) client.close();
        stopCountdown();
        showOnly(false, false, false);
        countdownLabel.setText("");
        setStatus("Đang kết nối tới máy chủ " + SERVER + " ...");
        client = new SignalingClient(this);
        client.connect(wsUri());
    }

    private static URI wsUri() {
        String base = SERVER.replaceAll("/+$", "");
        return URI.create(base.replaceFirst("^http", "ws") + "/ws");
    }

    @Override
    public void onOpen() {
        client.send("create_session");
    }

    @Override
    public void onMessage(JsonObject message) {
        String type = message.has("type") ? message.get("type").getAsString() : "";
        Platform.runLater(() -> handle(type, message));
    }

    @Override
    public void onClosed(String reason) {
        Platform.runLater(() -> connectionLost("Máy chủ đã đóng kết nối."));
    }

    @Override
    public void onError(Throwable error) {
        Platform.runLater(() -> connectionLost("Không kết nối được máy chủ: " + rootMessage(error)));
    }

    // ---------- xử lý tin nhắn (chạy trên luồng giao diện) ----------

    private void handle(String type, JsonObject m) {
        switch (type) {
            case "session_created" -> {
                iceServers = m.has("iceServers") && m.get("iceServers").isJsonArray()
                        ? m.getAsJsonArray("iceServers") : new JsonArray();
                showQr(m.get("sessionId").getAsString(),
                        m.get("token").getAsString(),
                        m.get("expiresInSec").getAsInt());
            }
            case "session_expired" -> {
                setStatus("Mã QR đã hết hạn, đang tạo mã mới...");
                client.send("create_session");
            }
            case "join_request" -> askApproval(m.get("device").getAsString());
            case "answer" -> {
                PeerSession p = peer;
                if (p != null && m.has("sdp")) {
                    String sdp = m.get("sdp").getAsString();
                    worker.execute(() -> p.handleAnswer(sdp));
                }
            }
            case "ice" -> {
                PeerSession p = peer;
                if (p != null && m.has("candidate")) {
                    String candidate = m.get("candidate").getAsString();
                    String mid = m.has("sdpMid") && !m.get("sdpMid").isJsonNull()
                            ? m.get("sdpMid").getAsString() : null;
                    int index = m.has("sdpMLineIndex") && !m.get("sdpMLineIndex").isJsonNull()
                            ? m.get("sdpMLineIndex").getAsInt() : 0;
                    worker.execute(() -> p.handleRemoteIce(mid, index, candidate));
                }
            }
            case "device_left" -> {
                deviceGone = true;
                closePeer();
                if (pendingDialog != null) pendingDialog.close();
                setStatus("Thiết bị đã rời đi, đang tạo mã mới...");
                client.send("create_session");
            }
            default -> { }
        }
    }

    private void showQr(String sessionId, String token, int expiresInSec) {
        String link = JoinLink.build(PUBLIC_URL, sessionId, token);
        qrView.setImage(QrImage.render(link, 280));
        linkField.setText(link);
        showOnly(true, true, false);
        setStatus("Quét mã QR bằng camera điện thoại để kết nối.");
        startCountdown(expiresInSec);
    }

    private void askApproval(String device) {
        stopCountdown();
        deviceGone = false;
        setStatus("Có thiết bị muốn kết nối: " + device);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle("PocketDock");
        alert.setHeaderText("Thiết bị “" + device + "” muốn kết nối");
        alert.setContentText("Chỉ cho phép nếu đó là thiết bị của bạn.");
        ButtonType allow = new ButtonType("Cho phép", ButtonBar.ButtonData.OK_DONE);
        ButtonType deny = new ButtonType("Từ chối", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(allow, deny);

        pendingDialog = alert;
        Optional<ButtonType> result = alert.showAndWait();
        pendingDialog = null;

        if (deviceGone) return; // điện thoại đã rời trong lúc hộp thoại đang mở

        if (result.isPresent() && result.get() == allow) {
            client.send("approve");
            showOnly(false, false, true);
            countdownLabel.setText("");
            setStatus("Đã kết nối với: " + device);
            startPeer();
        } else {
            client.send("reject");
            setStatus("Đã từ chối thiết bị, đang tạo mã mới...");
            client.send("create_session");
        }
    }

    private void disconnectDevice() {
        closePeer();
        client.send("bye");
        setStatus("Đã ngắt kết nối thiết bị, đang tạo mã mới...");
        client.send("create_session");
    }

    private void connectionLost(String text) {
        closePeer();
        stopCountdown();
        countdownLabel.setText("");
        showOnly(false, false, false);
        setShown(retryButton, true);
        setStatus(text);
    }

    // ---------- nối trực tiếp với điện thoại ----------

    private void startPeer() {
        closePeer();
        peerLabel.setText("Kết nối trực tiếp: đang khởi tạo...");
        channelLabel.setText("Kênh điều khiển: chưa mở");
        messagesLabel.setText("");
        recentMessages.clear();

        PeerSession session = new PeerSession(iceServers, new PeerSession.Listener() {
            @Override
            public void onSignal(JsonObject message) {
                SignalingClient c = client;
                if (c != null) c.send(message);
            }

            @Override
            public void onPeerState(String text) {
                Platform.runLater(() -> peerLabel.setText(text));
            }

            @Override
            public void onChannelState(String text) {
                Platform.runLater(() -> channelLabel.setText(text));
            }

            @Override
            public void onControlMessage(String text) {
                Platform.runLater(() -> addControlMessage(text));
            }
        });
        peer = session;

        worker.execute(() -> {
            try {
                session.start();
            } catch (Throwable t) {
                Platform.runLater(() -> peerLabel.setText("Lỗi khởi tạo WebRTC: " + rootMessage(t)));
            }
        });
    }

    private void closePeer() {
        PeerSession p = peer;
        peer = null;
        if (p != null) worker.execute(p::close);
    }

    private void addControlMessage(String text) {
        recentMessages.addLast(text);
        while (recentMessages.size() > 4) recentMessages.removeFirst();
        messagesLabel.setText("Tin nhắn từ điện thoại:\n" + String.join("\n", recentMessages));
    }

    // ---------- đồng hồ đếm ngược ----------

    private void startCountdown(int seconds) {
        stopCountdown();
        secondsLeft = seconds;
        updateCountdownLabel();
        countdown = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            secondsLeft--;
            updateCountdownLabel();
        }));
        countdown.setCycleCount(Timeline.INDEFINITE);
        countdown.play();
    }

    private void stopCountdown() {
        if (countdown != null) countdown.stop();
    }

    private void updateCountdownLabel() {
        if (secondsLeft > 0) {
            countdownLabel.setText(String.format("Mã hết hạn sau %d:%02d", secondsLeft / 60, secondsLeft % 60));
        } else {
            countdownLabel.setText("Mã đã hết hạn, đang làm mới...");
        }
    }

    // ---------- tiện ích giao diện ----------

    private void showOnly(boolean qr, boolean link, boolean connected) {
        setShown(qrView, qr);
        setShown(linkHint, link);
        setShown(linkField, link);
        setShown(disconnectButton, connected);
        setShown(peerLabel, connected);
        setShown(channelLabel, connected);
        setShown(messagesLabel, connected);
        setShown(retryButton, false);
    }

    private static void setShown(Node node, boolean on) {
        node.setVisible(on);
        node.setManaged(on);
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
