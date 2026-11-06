package com.pocketdock;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;

/** Tab "Cài đặt": lưu các lựa chọn vào bảng settings của cơ sở dữ liệu. */
public final class SettingsView {
    public static final String KEY_QUALITY = "screen_quality";

    private final VBox root = new VBox(10);
    private final Label saved = new Label();

    /** db có thể là null nếu không mở được cơ sở dữ liệu: khi đó các lựa chọn bị khóa. */
    public SettingsView(Database db, String serverUrl, String publicUrl) {
        Label heading = new Label("Chất lượng màn hình phụ");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");

        RadioButton r720 = new RadioButton("720p, 30 khung hình/giây (mặc định, nhẹ máy)");
        RadioButton r1080 = new RadioButton("1080p, 30 khung hình/giây (nặng máy hơn)");
        ToggleGroup group = new ToggleGroup();
        r720.setToggleGroup(group);
        r1080.setToggleGroup(group);

        Label note = new Label("Lựa chọn được lưu lại và sẽ được dùng khi chụp màn hình thật trên Windows (mốc M6).");
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #666666;");

        String current = "720p";
        if (db == null) {
            r720.setDisable(true);
            r1080.setDisable(true);
            saved.setText("Chưa mở được cơ sở dữ liệu nên chưa lưu được cài đặt.");
        } else {
            try {
                current = UiText.normalizeQuality(db.getSetting(KEY_QUALITY, "720p"));
            } catch (RuntimeException e) {
                saved.setText("Không đọc được cài đặt: " + e.getMessage());
            }
        }
        (current.equals("1080p") ? r1080 : r720).setSelected(true);

        // Gắn trình nghe sau khi chọn sẵn, để lúc mở màn hình không ghi lại cài đặt
        group.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            if (db == null || newValue == null) return;
            String value = newValue == r1080 ? "1080p" : "720p";
            try {
                db.setSetting(KEY_QUALITY, value);
                saved.setText("Đã lưu: " + value);
            } catch (RuntimeException e) {
                saved.setText("Không lưu được: " + e.getMessage());
            }
        });

        Label info = new Label("Máy chủ: " + serverUrl
                + "\nĐịa chỉ cho điện thoại: " + publicUrl
                + "\nCơ sở dữ liệu: " + (db == null ? "chưa mở được" : db.path()));
        info.setWrapText(true);
        info.setStyle("-fx-text-fill: #444444;");

        root.getChildren().addAll(heading, r720, r1080, note, saved, new Label(" "), info);
        root.setPadding(new Insets(16));
    }

    public Node node() {
        return root;
    }
}
