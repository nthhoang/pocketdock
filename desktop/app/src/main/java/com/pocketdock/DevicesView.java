package com.pocketdock;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Tab "Thiết bị": danh sách thiết bị đã ghép nối và các phiên gần đây, đọc từ cơ sở dữ liệu. */
public final class DevicesView {
    private final Supplier<Database> dbSupplier;
    private final Predicate<String> inUse;
    private final TableView<Database.DeviceRow> devices = new TableView<>();
    private final TableView<Database.SessionRow> sessions = new TableView<>();
    private final Label message = new Label();
    private final VBox root = new VBox(8);

    /** inUse cho biết thiết bị (theo tên) có đang kết nối không, để không cho xóa nhầm. */
    public DevicesView(Supplier<Database> dbSupplier, Predicate<String> inUse) {
        this.dbSupplier = dbSupplier;
        this.inUse = inUse;

        TableColumn<Database.DeviceRow, String> dName = column("Thiết bị", 160, Database.DeviceRow::name);
        TableColumn<Database.DeviceRow, String> dFirst = column("Lần đầu", 130, r -> UiText.time(r.firstSeen()));
        TableColumn<Database.DeviceRow, String> dLast = column("Gần nhất", 130, r -> UiText.time(r.lastSeen()));
        TableColumn<Database.DeviceRow, String> dCount = column("Số lần", 60, r -> String.valueOf(r.timesConnected()));
        devices.getColumns().add(dName);
        devices.getColumns().add(dFirst);
        devices.getColumns().add(dLast);
        devices.getColumns().add(dCount);
        devices.setPlaceholder(new Label("Chưa có thiết bị nào"));
        devices.setPrefHeight(170);

        TableColumn<Database.SessionRow, String> sName = column("Thiết bị", 130, Database.SessionRow::deviceName);
        TableColumn<Database.SessionRow, String> sStart = column("Bắt đầu", 130, r -> UiText.time(r.startedAt()));
        TableColumn<Database.SessionRow, String> sLength = column("Thời lượng", 90, r -> UiText.duration(r.startedAt(), r.endedAt()));
        TableColumn<Database.SessionRow, String> sReason = column("Kết thúc vì", 150, r -> UiText.reason(r.endReason()));
        sessions.getColumns().add(sName);
        sessions.getColumns().add(sStart);
        sessions.getColumns().add(sLength);
        sessions.getColumns().add(sReason);
        sessions.setPlaceholder(new Label("Chưa có phiên nào"));
        sessions.setPrefHeight(260);

        Button refreshButton = new Button("Làm mới");
        refreshButton.setOnAction(e -> refresh());
        Button deleteButton = new Button("Xóa thiết bị đã chọn");
        deleteButton.setOnAction(e -> deleteSelected());
        deleteButton.disableProperty().bind(devices.getSelectionModel().selectedItemProperty().isNull());

        Label devicesTitle = new Label("Thiết bị đã ghép nối");
        devicesTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label sessionsTitle = new Label("Các phiên gần đây");
        sessionsTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        message.setWrapText(true);

        root.getChildren().addAll(devicesTitle, devices, new HBox(8, refreshButton, deleteButton),
                sessionsTitle, sessions, message);
        root.setPadding(new Insets(12));
    }

    public Node node() {
        return root;
    }

    /** Đọc lại dữ liệu. Gọi trên luồng giao diện. Không bao giờ ném lỗi ra ngoài. */
    public void refresh() {
        Database db = dbSupplier.get();
        if (db == null) {
            devices.getItems().clear();
            sessions.getItems().clear();
            message.setText("Chưa mở được cơ sở dữ liệu.");
            return;
        }
        try {
            devices.getItems().setAll(db.listDevices());
            sessions.getItems().setAll(db.listSessions(50));
            message.setText(devices.getItems().size() + " thiết bị, " + db.countSessions()
                    + " phiên (hiện tối đa 50 phiên gần nhất)");
        } catch (RuntimeException e) {
            message.setText("Không đọc được dữ liệu: " + e.getMessage());
        }
    }

    private void deleteSelected() {
        Database db = dbSupplier.get();
        Database.DeviceRow row = devices.getSelectionModel().getSelectedItem();
        if (db == null || row == null) return;
        if (inUse.test(row.name())) {
            message.setText("Thiết bị này đang kết nối. Hãy ngắt kết nối trước khi xóa.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Xóa \"" + row.name() + "\" và toàn bộ lịch sử phiên của thiết bị này?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            db.deleteDevice(row.id());
        } catch (RuntimeException e) {
            message.setText("Không xóa được: " + e.getMessage());
            return;
        }
        refresh();
    }

    private static <T> TableColumn<T, String> column(String title, double width, Function<T, String> text) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue())));
        return column;
    }
}
