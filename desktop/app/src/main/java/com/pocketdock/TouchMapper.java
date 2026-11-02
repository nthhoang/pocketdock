package com.pocketdock;

import java.awt.Point;
import java.util.Optional;

/** Đổi tọa độ chạm chuẩn hóa (0 đến 1) sang tọa độ màn hình logic của máy tính. */
public final class TouchMapper {

    /** Vùng màn hình logic: gốc (x, y) có thể âm khi có nhiều màn hình. */
    public record Bounds(int x, int y, int width, int height) {
        public Bounds {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Kích thước màn hình phải lớn hơn 0");
            }
        }
    }

    private TouchMapper() { }

    /** Trả về rỗng nếu tọa độ không phải số hợp lệ. Giá trị ngoài 0..1 được kẹp vào mép. */
    public static Optional<Point> map(double nx, double ny, Bounds b) {
        if (!Double.isFinite(nx) || !Double.isFinite(ny)) return Optional.empty();
        double cx = Math.min(1.0, Math.max(0.0, nx));
        double cy = Math.min(1.0, Math.max(0.0, ny));
        int px = b.x() + (int) Math.round(cx * (b.width() - 1));
        int py = b.y() + (int) Math.round(cy * (b.height() - 1));
        return Optional.of(new Point(px, py));
    }
}
