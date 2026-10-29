package com.pocketdock.probe;

import java.io.IOException;
import java.io.PrintWriter;
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

    @Override
    public void close() {
        file.close();
    }
}
