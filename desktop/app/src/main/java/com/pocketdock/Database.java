package com.pocketdock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Cơ sở dữ liệu SQLite của máy tính: thiết bị đã ghép nối, các phiên và cài đặt. */
public final class Database implements AutoCloseable {

    public record DeviceRow(long id, String name, String firstSeen, String lastSeen, int timesConnected) { }

    public record SessionRow(long id, String deviceName, String startedAt, String endedAt, String endReason) { }

    private final Connection conn;
    private final String path;

    private Database(Connection conn, String path) {
        this.conn = conn;
        this.path = path;
    }

    /** Mặc định: ~/.pocketdock/pocketdock.db, đổi bằng biến môi trường POCKETDOCK_DB. */
    public static Path defaultPath() {
        String env = System.getenv("POCKETDOCK_DB");
        if (env != null && !env.isBlank()) return Path.of(env.trim());
        return Path.of(System.getProperty("user.home"), ".pocketdock", "pocketdock.db");
    }

    public static Database open(Path file) {
        Path abs = file.toAbsolutePath();
        try {
            Path parent = abs.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("Không tạo được thư mục " + abs.getParent(), e);
        }
        return openUrl("jdbc:sqlite:" + abs, abs.toString());
    }

    /** Dùng cho kiểm thử: cơ sở dữ liệu tạm trong bộ nhớ. */
    public static Database openMemory() {
        return openUrl("jdbc:sqlite::memory:", ":memory:");
    }

    private static Database openUrl(String url, String path) {
        try {
            Connection conn = DriverManager.getConnection(url);
            Database database = new Database(conn, path);
            database.createSchema();
            return database;
        } catch (SQLException e) {
            throw new IllegalStateException("Không mở được cơ sở dữ liệu: " + e.getMessage(), e);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("CREATE TABLE IF NOT EXISTS devices ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "name TEXT NOT NULL UNIQUE, "
                    + "first_seen TEXT NOT NULL, "
                    + "last_seen TEXT NOT NULL, "
                    + "times_connected INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS sessions ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "device_id INTEGER NOT NULL REFERENCES devices(id), "
                    + "started_at TEXT NOT NULL, "
                    + "ended_at TEXT, "
                    + "end_reason TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS settings ("
                    + "key TEXT PRIMARY KEY, "
                    + "value TEXT NOT NULL)");
            st.execute("PRAGMA user_version = 1");
        }
    }

    public String path() {
        return path;
    }

    /** Ghi nhận thiết bị vừa được cho phép và mở một phiên mới. Trả về mã phiên. */
    public synchronized long startSession(String deviceName) {
        String now = Instant.now().toString();
        try {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement up = conn.prepareStatement(
                        "INSERT INTO devices(name, first_seen, last_seen, times_connected) VALUES(?, ?, ?, 1) "
                                + "ON CONFLICT(name) DO UPDATE SET last_seen = excluded.last_seen, "
                                + "times_connected = times_connected + 1")) {
                    up.setString(1, deviceName);
                    up.setString(2, now);
                    up.setString(3, now);
                    up.executeUpdate();
                }
                long deviceId;
                try (PreparedStatement q = conn.prepareStatement("SELECT id FROM devices WHERE name = ?")) {
                    q.setString(1, deviceName);
                    try (ResultSet rs = q.executeQuery()) {
                        rs.next();
                        deviceId = rs.getLong(1);
                    }
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO sessions(device_id, started_at) VALUES(?, ?)")) {
                    ins.setLong(1, deviceId);
                    ins.setString(2, now);
                    ins.executeUpdate();
                }
                long sessionId;
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                    rs.next();
                    sessionId = rs.getLong(1);
                }
                conn.commit();
                return sessionId;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Không ghi được phiên: " + e.getMessage(), e);
        }
    }

    /** Đóng phiên. Phiên đã đóng rồi thì giữ nguyên lý do đầu tiên. */
    public synchronized void endSession(long sessionId, String reason) {
        try (PreparedStatement st = conn.prepareStatement(
                "UPDATE sessions SET ended_at = ?, end_reason = ? WHERE id = ? AND ended_at IS NULL")) {
            st.setString(1, Instant.now().toString());
            st.setString(2, reason);
            st.setLong(3, sessionId);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Không đóng được phiên: " + e.getMessage(), e);
        }
    }

    /** Phiên còn mở từ lần chạy trước (ứng dụng tắt đột ngột) được đánh dấu là bị gián đoạn. */
    public synchronized int closeStaleSessions() {
        try (PreparedStatement st = conn.prepareStatement(
                "UPDATE sessions SET ended_at = ?, end_reason = 'interrupted' WHERE ended_at IS NULL")) {
            st.setString(1, Instant.now().toString());
            return st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Không dọn được phiên cũ: " + e.getMessage(), e);
        }
    }

    public synchronized List<DeviceRow> listDevices() {
        List<DeviceRow> result = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, name, first_seen, last_seen, times_connected FROM devices ORDER BY last_seen DESC")) {
            while (rs.next()) {
                result.add(new DeviceRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Không đọc được danh sách thiết bị: " + e.getMessage(), e);
        }
        return result;
    }

    public synchronized List<SessionRow> listSessions(int limit) {
        List<SessionRow> result = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT s.id, d.name, s.started_at, s.ended_at, s.end_reason FROM sessions s "
                        + "JOIN devices d ON d.id = s.device_id ORDER BY s.id DESC LIMIT ?")) {
            st.setInt(1, limit);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    result.add(new SessionRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Không đọc được danh sách phiên: " + e.getMessage(), e);
        }
        return result;
    }

    /** Xóa một thiết bị cùng toàn bộ các phiên của nó. */
    public synchronized void deleteDevice(long deviceId) {
        try {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement st = conn.prepareStatement("DELETE FROM sessions WHERE device_id = ?")) {
                    st.setLong(1, deviceId);
                    st.executeUpdate();
                }
                try (PreparedStatement st = conn.prepareStatement("DELETE FROM devices WHERE id = ?")) {
                    st.setLong(1, deviceId);
                    st.executeUpdate();
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Không xóa được thiết bị: " + e.getMessage(), e);
        }
    }

    public int countDevices() {
        return count("devices");
    }

    public int countSessions() {
        return count("sessions");
    }

    private synchronized int count(String table) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Không đếm được bảng " + table + ": " + e.getMessage(), e);
        }
    }

    public synchronized String getSetting(String key, String fallback) {
        try (PreparedStatement st = conn.prepareStatement("SELECT value FROM settings WHERE key = ?")) {
            st.setString(1, key);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : fallback;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Không đọc được cài đặt: " + e.getMessage(), e);
        }
    }

    public synchronized void setSetting(String key, String value) {
        try (PreparedStatement st = conn.prepareStatement(
                "INSERT INTO settings(key, value) VALUES(?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            st.setString(1, key);
            st.setString(2, value);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Không ghi được cài đặt: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // đóng lỗi thì bỏ qua
        }
    }
}
