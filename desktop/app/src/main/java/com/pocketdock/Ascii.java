package com.pocketdock;

import java.text.Normalizer;

/** Bỏ dấu tiếng Việt, dùng khi in ra console Windows (hay lỗi font). */
public final class Ascii {

    private Ascii() {}

    public static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.replace('đ', 'd').replace('Đ', 'D');
    }
}
