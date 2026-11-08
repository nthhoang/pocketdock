package com.pocketdock;

/** Chất lượng chụp màn hình: đổi giá trị "720p" hoặc "1080p" trong cài đặt thành kích thước và tốc độ khung hình. */
public record ScreenQuality(String label, int width, int height, int fps) {

    /** Giá trị lạ hoặc rỗng thì dùng mặc định 720p. */
    public static ScreenQuality of(String value) {
        if ("1080p".equals(UiText.normalizeQuality(value))) {
            return new ScreenQuality("1080p", 1920, 1080, 30);
        }
        return new ScreenQuality("720p", 1280, 720, 30);
    }

    public String describe() {
        return width + "x" + height + " @ " + fps + " khung/giay";
    }
}
