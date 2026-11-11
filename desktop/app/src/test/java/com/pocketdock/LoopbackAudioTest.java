package com.pocketdock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoopbackAudioTest {

    @Test
    void docDungTungKhucVaBoPhanLe() throws IOException {
        byte[] data = new byte[LoopbackAudio.CHUNK_BYTES * 2 + 100];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
        List<byte[]> got = new ArrayList<>();
        long n = LoopbackAudio.pumpChunks(new ByteArrayInputStream(data), c -> got.add(c.clone()));
        assertEquals(2, n);
        assertEquals(2, got.size());
        assertEquals(LoopbackAudio.CHUNK_BYTES, got.get(0).length);
        assertEquals(data[0], got.get(0)[0]);
        assertEquals(data[LoopbackAudio.CHUNK_BYTES], got.get(1)[0]);
    }

    @Test
    void duLieuRongThiKhongCoKhuc() throws IOException {
        assertEquals(0, LoopbackAudio.pumpChunks(new ByteArrayInputStream(new byte[0]), c -> {
            throw new AssertionError("khong duoc goi");
        }));
    }

    @Test
    void duLieuNganHonMotKhucThiBoQua() throws IOException {
        assertEquals(0, LoopbackAudio.pumpChunks(new ByteArrayInputStream(new byte[1919]), c -> {
            throw new AssertionError("khong duoc goi");
        }));
    }

    @Test
    void timChuongTrinhPhu(@TempDir Path dir) throws IOException {
        Path exe = Files.createFile(dir.resolve("PocketDock.AudioHelper.exe"));
        assertEquals(exe.toAbsolutePath().normalize(),
                LoopbackAudio.firstExisting(List.of(dir.resolve("khong-co.exe"), exe)));
        assertNull(LoopbackAudio.firstExisting(List.of(dir.resolve("khong-co.exe"), dir.resolve("cung-khong.exe"))));
    }
}
