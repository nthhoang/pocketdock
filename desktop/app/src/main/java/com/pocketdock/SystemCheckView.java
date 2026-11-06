package com.pocketdock;

import java.util.List;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Cửa sổ "Kiểm tra hệ thống": chạy SystemCheck ở luồng nền rồi hiện báo cáo. */
public final class SystemCheckView {
    private final Stage stage = new Stage();
    private final TextArea output = new TextArea();
    private final Label summary = new Label();
    private final Button runButton = new Button("Chạy lại");
    private final Button copyButton = new Button("Sao chép báo cáo");
    private final Supplier<SystemCheck.Context> contextSupplier;
    private String lastReport = "";
    private boolean running;

    public SystemCheckView(Stage owner, Supplier<SystemCheck.Context> contextSupplier) {
        this.contextSupplier = contextSupplier;
        stage.initOwner(owner);
        stage.setTitle("Kiểm tra hệ thống");

        output.setEditable(false);
        output.setWrapText(true);
        output.setStyle("-fx-font-family: monospace;");
        VBox.setVgrow(output, Priority.ALWAYS);

        runButton.setOnAction(e -> run());
        copyButton.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(lastReport);
            Clipboard.getSystemClipboard().setContent(content);
            summary.setText("Đã sao chép báo cáo vào bộ nhớ tạm");
        });
        Button closeButton = new Button("Đóng");
        closeButton.setOnAction(e -> stage.hide());

        HBox buttons = new HBox(8, runButton, copyButton, closeButton);
        Label intro = new Label("Báo cáo cho biết máy còn thiếu gì để PocketDock chạy được.");
        VBox root = new VBox(8, intro, summary, output, buttons);
        root.setPadding(new Insets(12));
        stage.setScene(new Scene(root, 640, 560));
    }

    public void show() {
        stage.show();
        stage.toFront();
        run();
    }

    private void run() {
        if (running) return;
        running = true;
        runButton.setDisable(true);
        summary.setText("Đang kiểm tra...");
        output.clear();
        SystemCheck.Context context = contextSupplier.get(); // lấy thông tin màn hình ở luồng giao diện
        Thread worker = new Thread(() -> {
            List<SystemCheck.Result> results = SystemCheck.runAll(context);
            String report = SystemCheck.toText(results);
            Platform.runLater(() -> {
                lastReport = report;
                output.setText(report);
                summary.setText("Kết quả: " + SystemCheck.summaryLine(results));
                EventLog.add("Kiem tra he thong: "
                        + SystemCheck.count(results, SystemCheck.Status.OK) + " dat, "
                        + SystemCheck.count(results, SystemCheck.Status.WARN) + " canh bao, "
                        + SystemCheck.count(results, SystemCheck.Status.FAIL) + " loi");
                runButton.setDisable(false);
                running = false;
            });
        }, "system-check");
        worker.setDaemon(true);
        worker.start();
    }
}
