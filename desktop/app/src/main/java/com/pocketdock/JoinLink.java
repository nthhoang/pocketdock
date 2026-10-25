package com.pocketdock;

public final class JoinLink {
    private JoinLink() {}

    /** Link nhúng trong mã QR: phần sau dấu # là mã phòng và token. */
    public static String build(String baseUrl, String sessionId, String token) {
        String base = baseUrl.replaceAll("/+$", "");
        return base + "/#" + sessionId + "." + token;
    }
}
