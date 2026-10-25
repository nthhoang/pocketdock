package com.pocketdock;

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
import java.util.Optional;

public class PocketDockApp extends Application implements SignalingClient.Handler {

    // Địa chỉ máy chủ để app kết nối, và địa chỉ công khai để ghi vào mã QR cho điện thoại mở
    private static final String SERVER = env("POCKETDOCK_SERVER", "http://localhost:8081");
    private static final String PUBLIC_URL = env("POCKETDOCK_PUBLIC_URL", SERVER);

    private Stage stage;
    private final ImageView qrView = new ImageView();
    private final Label countdownLabel = new Label();
    private final Label statusLabel = new Label();
    private final Label linkHint = new Label("Không quét được? Sao chép địa chỉ này:");
    private final TextField linkField = new TextField();
    private final Button retryButton = new Button("Kết nối lại");
    private final Button disconnectButton = new Button("Ngắt kết nối thiết bị");

    private volatile SignalingClient client;
    private Timeline countdown;
    private int secondsLeft;
    private Alert pendingDialog;
    private boolean deviceGone;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;

        Label title = new Label("PocketDock");
        title.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");

        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(340);
        statusLabel.setStyle("-fx-font-size: 15px;");
        countdownLabel.setStyle("-fx-text-fill: #666666;");
        linkHint.setStyle("-fx-text-fill: #666666; -fx-font-size: 12px;");

        qrView.setFitWidth(280);
        qrView.setFitHeight(280);
        qrView.setSmooth(false);

        linkField.setEditable(false);
        linkField.setMaxWidth(340);

        retryButton.setOnAction(e -> connect());
        disconnectButton.setOnAction(e -> disconnectDevice());

        VBox root = new VBox(12, title, qrView, countdownLabel, statusLabel,
                linkHint, linkField, retryButton, disconnectButton);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(24));

        stage.setTitle("PocketDock");
        stage.setScene(new Scene(root, 420, 660));
        stage.show();

        connect();
    }

    @Override
    public void stop() {
        if (client != null) client.close();
    }

    // ---------- kết nối máy chủ ----------

    private void connect() {
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
            case "session_created" -> showQr(
                    m.get("sessionId").getAsString(),
                    m.get("token").getAsString(),
                    m.get("expiresInSec").getAsInt());
            case "session_expired" -> {
                setStatus("Mã QR đã hết hạn, đang tạo mã mới...");
                client.send("create_session");
            }
            case "join_request" -> askApproval(m.get("device").getAsString());
            case "device_left" -> {
                deviceGone = true;
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
        } else {
            client.send("reject");
            setStatus("Đã từ chối thiết bị, đang tạo mã mới...");
            client.send("create_session");
        }
    }

    private void disconnectDevice() {
        client.send("bye");
        setStatus("Đã ngắt kết nối thiết bị, đang tạo mã mới...");
        client.send("create_session");
    }

    private void connectionLost(String text) {
        stopCountdown();
        countdownLabel.setText("");
        showOnly(false, false, false);
        setShown(retryButton, true);
        setStatus(text);
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

    private void showOnly(boolean qr, boolean link, boolean disconnect) {
        setShown(qrView, qr);
        setShown(linkHint, link);
        setShown(linkField, link);
        setShown(disconnectButton, disconnect);
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
