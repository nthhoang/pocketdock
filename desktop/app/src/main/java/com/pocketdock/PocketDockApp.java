package com.pocketdock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.net.URI;
import java.nio.file.Path;
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
    private final Label mediaLabel = new Label();
    private final Label signalLabel = new Label();
    private final Label messagesLabel = new Label();
    private final Label touchLabel = new Label();
    private long touchCount;
    private MouseController mouse;
    private boolean mouseTried;
    private final Label linkHint = new Label("Không quét được? Sao chép địa chỉ này:");
    private final TextField linkField = new TextField();
    private final Button retryButton = new Button("Kết nối lại");
    private final Button disconnectButton = new Button("Ngắt kết nối thiết bị");
    private final Button checkButton = new Button("Kiểm tra hệ thống");
    private SystemCheckView checkView;
    private DevicesView devicesView;

    private volatile SignalingClient client;
    private Timeline countdown;
    private int secondsLeft;
    private Alert pendingDialog;
    private boolean deviceGone;
    private Database db;
    private long dbSessionId = -1;
    private String endReason = "closed";
    private String currentDevice = "";

    // Giữ phòng và tự nối lại khi mất kết nối tới máy chủ
    private volatile String sessionId;
    private volatile String resumeToken;
    private volatile boolean resumeMode;
    private int resumeWindowSec = 90;
    private boolean sessionActive;
    private boolean phoneAway;
    private volatile String peerStateCode = "NEW";
    private volatile int peerGeneration;
    private Timeline reconnectTimer;
    private Timeline peerCheck;
    private int reconnectAttempt;
    private long resumeDeadlineNs;

    private JsonArray iceServers = new JsonArray();
    private volatile PeerSession peer;
    private final Deque<String> recentMessages = new ArrayDeque<>();
    // Mọi thao tác WebRTC chạy tuần tự trên một luồng riêng, để không làm đơ giao diện
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "webrtc-worker");
        t.setDaemon(true);
        return t;
    });

    // Cửa sổ xem webcam từ điện thoại
    private Stage previewStage;
    private final RemoteVideoView videoView = new RemoteVideoView();
    private final Label previewInfo = new Label();
    private final CheckBox flipBox = new CheckBox("Lật ngang");
    private final ToggleButton muteButton = new ToggleButton("Tắt tiếng micro điện thoại");
    private long lastFrames;
    private String webcamState = "Webcam điện thoại: tắt";
    private String micState = "Micro điện thoại: tắt";

    // Theo dõi tín hiệu từ điện thoại (nhịp tim 2 giây một lần)
    private Timeline ticker;
    private long lastSignalNs;
    private boolean inGap;
    private int gapCount;
    private double longestGapSec;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        openDatabase();

        Label title = new Label("PocketDock");
        title.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");

        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(380);
        statusLabel.setStyle("-fx-font-size: 15px;");
        countdownLabel.setStyle("-fx-text-fill: #666666;");
        linkHint.setStyle("-fx-text-fill: #666666; -fx-font-size: 12px;");
        for (Label label : new Label[]{peerLabel, channelLabel, mediaLabel, signalLabel, messagesLabel, touchLabel}) {
            label.setWrapText(true);
            label.setMaxWidth(380);
            label.setStyle("-fx-font-size: 13px;");
        }

        qrView.setFitWidth(280);
        qrView.setFitHeight(280);
        qrView.setSmooth(false);

        linkField.setEditable(false);
        linkField.setMaxWidth(380);

        retryButton.setOnAction(e -> connect());
        disconnectButton.setOnAction(e -> disconnectDevice());
        checkButton.setOnAction(e -> showSystemCheck());

        VBox root = new VBox(10, title, qrView, countdownLabel, statusLabel,
                peerLabel, channelLabel, mediaLabel, signalLabel, messagesLabel, touchLabel,
                linkHint, linkField, retryButton, disconnectButton, checkButton);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(24));

        // Màn hình chính có ba tab: Kết nối (như cũ, có thanh cuộn), Thiết bị, Cài đặt
        ScrollPane connectPane = new ScrollPane(root);
        connectPane.setFitToWidth(true);
        Tab connectTab = new Tab("Kết nối", connectPane);
        devicesView = new DevicesView(() -> db, name -> sessionActive && name.equals(currentDevice));
        Tab devicesTab = new Tab("Thiết bị", devicesView.node());
        Tab settingsTab = new Tab("Cài đặt", new SettingsView(db, SERVER, PUBLIC_URL).node());
        connectTab.setClosable(false);
        devicesTab.setClosable(false);
        settingsTab.setClosable(false);
        TabPane tabs = new TabPane(connectTab, devicesTab, settingsTab);
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, oldTab, newTab) -> {
            if (newTab == devicesTab) devicesView.refresh();
        });

        stage.setTitle("PocketDock");
        stage.setScene(new Scene(tabs, 480, 760));
        stage.show();

        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();

        connect();
    }

    @Override
    public void stop() {
        if (ticker != null) ticker.stop();
        endReason = "app_closed";
        closePeer();
        if (mouse != null) mouse.close();
        if (client != null) client.close();
        worker.execute(RtcRuntime::shutdown);
        worker.shutdown();
        try {
            worker.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (db != null) db.close();
    }

    // ---------- kết nối máy chủ ----------

    private void connect() {
        cancelReconnectTimer();
        resumeMode = false;
        sessionActive = false;
        phoneAway = false;
        resumeToken = null;
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
        if (resumeMode && sessionId != null && resumeToken != null) {
            JsonObject resume = new JsonObject();
            resume.addProperty("type", "resume");
            resume.addProperty("role", "desktop");
            resume.addProperty("sessionId", sessionId);
            resume.addProperty("resumeToken", resumeToken);
            client.send(resume);
        } else {
            client.send("create_session");
        }
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
                sessionId = m.get("sessionId").getAsString();
                resumeToken = null;
                sessionActive = false;
                phoneAway = false;
                reconnectAttempt = 0;
                iceServers = m.has("iceServers") && m.get("iceServers").isJsonArray()
                        ? m.getAsJsonArray("iceServers") : new JsonArray();
                showQr(m.get("sessionId").getAsString(),
                        m.get("token").getAsString(),
                        m.get("expiresInSec").getAsInt());
            }
            case "resume_info" -> {
                String token = str(m, "resumeToken");
                if (token != null) resumeToken = token;
                if (m.has("resumeWindowSec")) resumeWindowSec = m.get("resumeWindowSec").getAsInt();
            }
            case "device_away" -> {
                phoneAway = true;
                if (m.has("resumeWindowSec")) resumeWindowSec = m.get("resumeWindowSec").getAsInt();
                EventLog.add("May chu bao: dien thoai tam vang, giu phong " + resumeWindowSec + " giay");
                setStatus("Điện thoại tạm vắng, đang chờ nối lại (tối đa " + resumeWindowSec + " giây)...");
            }
            case "device_back" -> {
                phoneAway = false;
                EventLog.add("May chu bao: dien thoai da quay lai");
                keepOrRestartPeer("dien thoai quay lai");
            }
            case "resumed" -> {
                resumeMode = false;
                cancelReconnectTimer();
                reconnectAttempt = 0;
                String token = str(m, "resumeToken");
                if (token != null) resumeToken = token;
                if (m.has("resumeWindowSec")) resumeWindowSec = m.get("resumeWindowSec").getAsInt();
                boolean phonePresent = m.has("phonePresent") && m.get("phonePresent").getAsBoolean();
                EventLog.add("May chu: da nhoi lai phong (dien thoai " + (phonePresent ? "con" : "dang vang") + ")");
                if (!phonePresent) {
                    phoneAway = true;
                    setStatus("Đã nối lại máy chủ, điện thoại đang tạm vắng...");
                } else {
                    phoneAway = false;
                    keepOrRestartPeer("nhoi lai phong");
                }
            }
            case "resume_rejected" -> {
                String reason = str(m, "reason");
                EventLog.add("May chu tu choi nhoi lai phong: " + reason);
                giveUpResume("may chu tu choi (" + reason + ")");
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
                EventLog.add("May chu bao: dien thoai da roi di");
                endReason = "phone_left";
                sessionActive = false;
                phoneAway = false;
                resumeToken = null;
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
            EventLog.add("---- Phien moi: da cho phep thiet bi " + device + " ----");
            currentDevice = device;
            sessionActive = true;
            phoneAway = false;
            startPeer();
            startDbSession(device);
        } else {
            client.send("reject");
            setStatus("Đã từ chối thiết bị, đang tạo mã mới...");
            client.send("create_session");
        }
    }

    private void disconnectDevice() {
        endReason = "desktop_disconnect";
        resumeMode = false;
        sessionActive = false;
        phoneAway = false;
        resumeToken = null;
        closePeer();
        client.send("bye");
        setStatus("Đã ngắt kết nối thiết bị, đang tạo mã mới...");
        client.send("create_session");
    }

    private void connectionLost(String text) {
        if (sessionActive && resumeToken != null && sessionId != null) {
            // Đang có điện thoại: giữ nguyên kết nối trực tiếp và thử nối lại phòng
            startResume(text);
            return;
        }
        endReason = "server_lost";
        closePeer();
        stopCountdown();
        countdownLabel.setText("");
        showOnly(false, false, false);
        setShown(retryButton, true);
        setStatus(text);
        scheduleRetry();
    }

    // ---------- nối trực tiếp với điện thoại ----------

    private void startPeer() {
        teardownPeer();
        peerStateCode = "NEW";
        peerLabel.setText("Kết nối trực tiếp: đang khởi tạo...");
        channelLabel.setText("Kênh điều khiển: chưa mở");
        webcamState = "Webcam điện thoại: tắt";
        micState = "Micro điện thoại: tắt";
        mediaLabel.setText(webcamState + "\n" + micState);
        signalLabel.setText("Tín hiệu điện thoại: chờ tin đầu tiên");
        messagesLabel.setText("");
        touchLabel.setText("Chạm từ điện thoại: chưa có");
        touchCount = 0;
        recentMessages.clear();
        lastSignalNs = 0;
        inGap = false;
        gapCount = 0;
        longestGapSec = 0;
        muteButton.setSelected(false);

        final int generation = ++peerGeneration;
        PeerSession session = new PeerSession(iceServers, new PeerSession.Listener() {
            @Override
            public void onSignal(JsonObject message) {
                SignalingClient c = client;
                if (c != null) c.send(message);
            }

            @Override
            public void onPeerState(String text) {
                EventLog.add("May tinh: " + text);
                Platform.runLater(() -> peerLabel.setText(text));
            }

            @Override
            public void onPeerStateCode(String code) {
                if (generation == peerGeneration) peerStateCode = code;
            }

            @Override
            public void onChannelState(String text) {
                EventLog.add("May tinh: " + text);
                Platform.runLater(() -> channelLabel.setText(text));
            }

            @Override
            public void onControlMessage(String text) {
                Platform.runLater(() -> handleControlText(text));
            }

            @Override
            public void onRemoteMedia(String kind, boolean on, String detail) {
                Platform.runLater(() -> handleRemoteMedia(kind, on, detail));
            }
        });
        session.setRemoteVideoSink(videoView);
        peer = session;

        worker.execute(() -> {
            try {
                session.setScreenQuality(db != null
                        ? db.getSetting(SettingsView.KEY_QUALITY, "720p") : "720p");
                session.start();
            } catch (Throwable t) {
                Platform.runLater(() -> peerLabel.setText("Lỗi khởi tạo WebRTC: " + rootMessage(t)));
            }
        });
    }

    // ---------- tự nối lại khi mất kết nối máy chủ ----------

    private static String str(JsonObject m, String key) {
        return m.has(key) && !m.get(key).isJsonNull() ? m.get(key).getAsString() : null;
    }

    /** Kết nối trực tiếp còn tốt: trạng thái đã nối và vừa nhận được tin từ điện thoại. */
    private boolean peerHealthy() {
        return peer != null
                && "CONNECTED".equals(peerStateCode)
                && lastSignalNs != 0
                && (System.nanoTime() - lastSignalNs) / 1e9 <= 6;
    }

    /**
     * Điện thoại hoặc phòng vừa nối lại: kết nối trực tiếp còn tốt thì giữ. Nếu chưa chắc (nhịp tim cuối đã cũ
     * vì điện thoại vừa mở lại trang) thì chờ tối đa 5 giây cho nhịp tim đầu tiên rồi mới quyết định dựng lại.
     */
    private void keepOrRestartPeer(String reason) {
        if (peerCheck != null) peerCheck.stop();
        if (peerHealthy()) {
            EventLog.add("Giu nguyen ket noi truc tiep");
            setStatus("Đã kết nối với: " + currentDevice);
            return;
        }
        if (peer == null || "FAILED".equals(peerStateCode) || "CLOSED".equals(peerStateCode)) {
            restartPeer(reason);
            return;
        }
        EventLog.add("Cho nhip tim dien thoai toi da 5 giay truoc khi quyet dinh dung lai");
        int[] left = {5};
        peerCheck = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (!sessionActive || phoneAway) {
                peerCheck.stop();
            } else if (peerHealthy()) {
                peerCheck.stop();
                EventLog.add("Giu nguyen ket noi truc tiep (nhip tim da tro lai)");
                setStatus("Đã kết nối với: " + currentDevice);
            } else if (--left[0] <= 0) {
                peerCheck.stop();
                restartPeer(reason);
            }
        }));
        peerCheck.setCycleCount(Timeline.INDEFINITE);
        peerCheck.play();
    }

    /** Dựng lại kết nối trực tiếp mà vẫn giữ nguyên phiên (không ghi đóng phiên vào CSDL). */
    private void restartPeer(String reason) {
        EventLog.add("Dung lai ket noi truc tiep: " + reason);
        SignalingClient c = client;
        if (c != null) c.send("restart");
        startPeer();
        setStatus("Đã kết nối với: " + currentDevice);
    }

    private void startResume(String reason) {
        if (!resumeMode) {
            resumeMode = true;
            reconnectAttempt = 0;
            resumeDeadlineNs = System.nanoTime() + resumeWindowSec * 1_000_000_000L;
            EventLog.add("Mat ket noi may chu (" + reason + "), thu nhoi lai phong trong " + resumeWindowSec + " giay");
        }
        scheduleResumeAttempt();
    }

    private void scheduleResumeAttempt() {
        cancelReconnectTimer();
        long left = (resumeDeadlineNs - System.nanoTime()) / 1_000_000_000L;
        if (left <= 0) {
            giveUpResume("het thoi gian giu phong");
            return;
        }
        int delay = Math.min(1 << Math.min(reconnectAttempt, 3), 8);
        reconnectAttempt++;
        setStatus("Mất kết nối máy chủ, thử nối lại sau " + delay + " giây (còn khoảng " + left + " giây giữ phòng)");
        reconnectTimer = new Timeline(new KeyFrame(Duration.seconds(delay), e -> doResumeConnect()));
        reconnectTimer.play();
    }

    private void doResumeConnect() {
        if (!resumeMode) return;
        SignalingClient old = client;
        if (old != null) old.close();
        client = new SignalingClient(this);
        client.connect(wsUri());
    }

    /** Không nối lại được phòng cũ: bỏ phiên cũ và tạo mã QR mới. */
    private void giveUpResume(String why) {
        EventLog.add("Bo nhoi lai phong: " + why);
        resumeMode = false;
        sessionActive = false;
        phoneAway = false;
        resumeToken = null;
        endReason = "server_lost";
        closePeer();
        connect();
    }

    /** Chưa có điện thoại: tự thử kết nối lại máy chủ, khoảng chờ tăng dần 2, 4, 8, 15 giây. */
    private void scheduleRetry() {
        cancelReconnectTimer();
        int delay = Math.min(2 << Math.min(reconnectAttempt, 3), 15);
        reconnectAttempt++;
        statusLabel.setText(statusLabel.getText() + " Tự thử lại sau " + delay + " giây.");
        reconnectTimer = new Timeline(new KeyFrame(Duration.seconds(delay), e -> connect()));
        reconnectTimer.play();
    }

    private void cancelReconnectTimer() {
        if (reconnectTimer != null) {
            reconnectTimer.stop();
            reconnectTimer = null;
        }
    }

    // ---------- kiểm tra hệ thống ----------

    private void showSystemCheck() {
        if (checkView == null) {
            checkView = new SystemCheckView(stage, () -> {
                Rectangle2D screen = Screen.getPrimary().getBounds();
                String screenInfo = (int) screen.getWidth() + "x" + (int) screen.getHeight()
                        + ", phóng " + Math.round(Screen.getPrimary().getOutputScaleX() * 100) + "%";
                return new SystemCheck.Context(SERVER, PUBLIC_URL, db, screenInfo, Path.of("ketqua").toAbsolutePath());
            });
        }
        checkView.show();
    }

    // ---------- cơ sở dữ liệu SQLite ----------

    private void openDatabase() {
        try {
            db = Database.open(Database.defaultPath());
            db.closeStaleSessions();
            EventLog.add("CSDL: " + db.path() + ", " + db.countDevices() + " thiet bi, " + db.countSessions() + " phien");
        } catch (RuntimeException e) {
            db = null;
            EventLog.add("Khong mo duoc CSDL: " + rootMessage(e));
        }
    }

    private void startDbSession(String device) {
        if (db == null) return;
        try {
            dbSessionId = db.startSession(device);
            if (devicesView != null) devicesView.refresh();
        } catch (RuntimeException e) {
            dbSessionId = -1;
            EventLog.add("Khong ghi duoc phien vao CSDL: " + rootMessage(e));
        }
    }

    private void endDbSession() {
        String reason = endReason;
        endReason = "closed";
        if (db == null || dbSessionId < 0) return;
        long id = dbSessionId;
        dbSessionId = -1;
        try {
            db.endSession(id, reason);
            if (devicesView != null) devicesView.refresh();
            EventLog.add("CSDL: dong phien " + id + " (" + reason + ")");
        } catch (RuntimeException e) {
            EventLog.add("Khong dong duoc phien trong CSDL: " + rootMessage(e));
        }
    }

    /** Gọi trên luồng giao diện. */
    private void closePeer() {
        endDbSession();
        teardownPeer();
    }

    /** Chỉ đóng kết nối trực tiếp hiện tại, không kết thúc phiên (dùng khi dựng lại kết nối). */
    private void teardownPeer() {
        PeerSession p = peer;
        peer = null;
        MouseController mc = mouse;
        if (mc != null) mc.releaseAll();
        if (p != null) worker.execute(p::close);
        resetMediaUi();
    }

    private void resetMediaUi() {
        if (previewStage != null) previewStage.hide();
        videoView.clear();
        lastFrames = videoView.framesTotal();
        lastSignalNs = 0;
        webcamState = "Webcam điện thoại: tắt";
        micState = "Micro điện thoại: tắt";
        mediaLabel.setText("");
        signalLabel.setText("");
    }

    private void handleControlText(String text) {
        lastSignalNs = System.nanoTime();
        if (text.contains("\"hb\"")) return; // nhịp tim chỉ dùng để theo dõi, không hiện ra
        if (text.contains("\"touch\"")) {
            handleTouch(text);
            return;
        }
        EventLog.add("Dien thoai: " + text);
        recentMessages.addLast(text);
        while (recentMessages.size() > 4) recentMessages.removeFirst();
        messagesLabel.setText("Tin nhắn từ điện thoại:\n" + String.join("\n", recentMessages));
    }

    /** Nhận tọa độ chạm từ điện thoại, đổi sang tọa độ màn hình, ghi log và điều khiển chuột thật khi được bật. */
    private void handleTouch(String text) {
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            String action = o.get("a").getAsString();
            double nx = o.get("x").getAsDouble();
            double ny = o.get("y").getAsDouble();
            Rectangle2D screen = Screen.getPrimary().getBounds();
            TouchMapper.Bounds bounds = new TouchMapper.Bounds(
                    (int) screen.getMinX(), (int) screen.getMinY(),
                    (int) screen.getWidth(), (int) screen.getHeight());
            MouseController mc = mouseController(bounds);
            if (mc != null) bounds = mc.bounds();
            var point = TouchMapper.map(nx, ny, bounds);
            if (point.isEmpty()) return;
            int touchId = o.has("id") ? o.get("id").getAsInt() : 0;
            if (mc != null) mc.handle(action, touchId, point.get());
            touchCount++;
            String line = action + " (" + nx + ", " + ny + ") -> màn hình (" + point.get().x + ", " + point.get().y + ")";
            touchLabel.setText("Chạm từ điện thoại: " + touchCount + " tin, mới nhất: " + line);
            if (!"move".equals(action)) {
                EventLog.add("Cham: " + action + " (" + nx + ", " + ny + ") -> (" + point.get().x + ", " + point.get().y + ")");
            }
        } catch (RuntimeException e) {
            EventLog.add("Tin cham khong hop le: " + text);
        }
    }

    /** Tạo bộ điều khiển chuột ở lần chạm đầu tiên. Trả về null nếu không bật (chỉ ghi log). */
    private MouseController mouseController(TouchMapper.Bounds javafxBounds) {
        if (!mouseTried) {
            mouseTried = true;
            mouse = MouseController.tryCreate();
            if (mouse != null) {
                TouchMapper.Bounds awt = mouse.bounds();
                EventLog.add("Chuot: dieu khien that bang Robot; vung AWT " + awt.x() + "," + awt.y() + " "
                        + awt.width() + "x" + awt.height() + ", vung JavaFX " + javafxBounds.x() + ","
                        + javafxBounds.y() + " " + javafxBounds.width() + "x" + javafxBounds.height());
            } else {
                EventLog.add("Chuot: khong dieu khien chuot that (chi ghi log)");
            }
        }
        return mouse;
    }

    private void handleRemoteMedia(String kind, boolean on, String detail) {
        String suffix = detail.isEmpty() ? "" : " (" + detail + ")";
        if ("webcam".equals(kind)) {
            webcamState = "Webcam điện thoại: " + (on ? "đang bật" : "tắt") + suffix;
            if (on) {
                ensurePreview();
                previewStage.show();
                previewStage.toFront();
            } else if (previewStage != null) {
                previewStage.hide();
            }
        } else if ("mic".equals(kind)) {
            micState = "Micro điện thoại: " + (on ? "đang bật" : "tắt") + suffix;
        }
        mediaLabel.setText(webcamState + "\n" + micState);
        EventLog.add("Dien thoai: " + kind + (on ? " BAT " : " TAT ") + detail);
    }

    // ---------- cửa sổ webcam ----------

    private void ensurePreview() {
        if (previewStage != null) return;

        ImageView view = videoView.getNode();
        view.setPreserveRatio(true);
        StackPane center = new StackPane(view);
        center.setStyle("-fx-background-color: black;");
        center.setMinSize(0, 0);
        view.fitWidthProperty().bind(center.widthProperty());
        view.fitHeightProperty().bind(center.heightProperty());

        flipBox.selectedProperty().addListener((o, was, now) -> view.setScaleX(now ? -1 : 1));
        muteButton.selectedProperty().addListener((o, was, now) -> {
            muteButton.setText(now ? "Bật lại tiếng micro điện thoại" : "Tắt tiếng micro điện thoại");
            setPhoneMicMuted(now);
        });
        Label warning = new Label("Dùng tai nghe để tránh bị hú");
        warning.setStyle("-fx-text-fill: #888888;");

        HBox bar = new HBox(14, previewInfo, flipBox, muteButton, warning);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));

        BorderPane pane = new BorderPane(center);
        pane.setBottom(bar);

        previewStage = new Stage();
        previewStage.setTitle("Webcam từ điện thoại");
        previewStage.setScene(new Scene(pane, 840, 540));
    }

    private void setPhoneMicMuted(boolean muted) {
        PeerSession p = peer;
        if (p != null) worker.execute(() -> p.setRemoteAudioVolume(muted ? 0.0 : 1.0));
    }

    // ---------- bộ đếm 1 giây: thống kê hình và theo dõi nhịp tim ----------

    private void tick() {
        if (previewStage != null && previewStage.isShowing()) {
            long total = videoView.framesTotal();
            long fps = total - lastFrames;
            lastFrames = total;
            previewInfo.setText(videoView.width() + "x" + videoView.height() + ", " + fps + " khung hình/giây");
        }

        if (peer == null || lastSignalNs == 0) return;
        double gap = (System.nanoTime() - lastSignalNs) / 1e9;
        if (gap > 6) {
            if (!inGap) {
                inGap = true;
                EventLog.add("MAT TIN HIEU tu dien thoai (qua 6 giay khong co tin)");
            }
            signalLabel.setText("Tín hiệu điện thoại: im lặng " + (int) gap + " giây" + gapSummary());
        } else {
            if (inGap) {
                inGap = false;
                gapCount++;
                longestGapSec = Math.max(longestGapSec, gap + 6);
                EventLog.add("Tin hieu dien thoai tro lai (im lang khoang " + Math.round(gap + 6) + " giay)");
            }
            signalLabel.setText("Tín hiệu điện thoại: bình thường" + gapSummary());
        }
    }

    private String gapSummary() {
        return gapCount == 0 ? "" : " (đã mất " + gapCount + " lần, lâu nhất khoảng " + Math.round(longestGapSec) + " giây)";
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
        setShown(mediaLabel, connected);
        setShown(signalLabel, connected);
        setShown(messagesLabel, connected);
        setShown(touchLabel, connected);
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
        String forced = AutoStack.override(key);
        if (forced != null) return forced;
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
