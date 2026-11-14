package com.pocketdock;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tìm địa chỉ trycloudflare.com mà cloudflared in ra khi tạo đường hầm. */
public final class TunnelUrl {

    // Loại "api.trycloudflare.com" vì đó là địa chỉ cloudflared gọi để xin đường hầm, không phải địa chỉ của mình.
    private static final Pattern URL = Pattern.compile("https://(?!api\\.)[a-z0-9-]+\\.trycloudflare\\.com");

    private TunnelUrl() {}

    /** Trả về địa chỉ đầu tiên tìm thấy trong dòng chữ, hoặc null. */
    public static String find(String line) {
        if (line == null) return null;
        Matcher m = URL.matcher(line.toLowerCase());
        return m.find() ? m.group() : null;
    }
}
