package com.pocketdock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Nhật ký sự kiện, dùng để ghi lại thử nghiệm khóa màn hình: ketqua/m4-nhat-ky.txt */
public final class EventLog {
    private static final Path FILE = Path.of("ketqua", "m4-nhat-ky.txt");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private EventLog() {}

    public static synchronized void add(String text) {
        try {
            Files.createDirectories(FILE.getParent());
            String line = LocalTime.now().format(TIME) + "  " + text + System.lineSeparator();
            Files.writeString(FILE, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // không ghi được thì bỏ qua, không ảnh hưởng chức năng
        }
    }
}
