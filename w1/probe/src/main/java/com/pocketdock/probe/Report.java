package com.pocketdock.probe;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Ghi kết quả vừa ra màn hình vừa ra file trong thư mục ketqua. */
final class Report implements AutoCloseable {
    private final PrintWriter file;

    Report(String fileName) throws IOException {
        Path dir = Path.of("ketqua");
        Files.createDirectories(dir);
        file = new PrintWriter(Files.newBufferedWriter(dir.resolve(fileName), StandardCharsets.UTF_8), true);
    }

    void line(String text) {
        System.out.println(text);
        file.println(text);
    }

    /** Ghi loi day du kem thong tin chan doan de tim nguyen nhan. */
    void trace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String[] lines = sw.toString().split("\\R");
        for (int i = 0; i < Math.min(lines.length, 30); i++) {
            line("    " + lines[i]);
        }
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.toLowerCase().contains("webrtc")) line("    classpath: " + entry);
        }
        line("    java.io.tmpdir = " + System.getProperty("java.io.tmpdir"));
    }

    @Override
    public void close() {
        file.close();
    }
}
