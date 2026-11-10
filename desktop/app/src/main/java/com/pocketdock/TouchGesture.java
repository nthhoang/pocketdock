package com.pocketdock;

import java.awt.Point;
import java.util.List;

/**
 * Đổi chuỗi sự kiện chạm (down, move, up) thành các thao tác chuột: di chuyển, nhấn, thả.
 * Chỉ ngón đầu tiên điều khiển chuột; ngón thứ hai bị bỏ qua cho đến khi ngón đầu nhấc lên.
 * Lớp thuần Java, không đụng tới Robot nên kiểm thử tự động được.
 */
public final class TouchGesture {

    public enum Kind { MOVE, PRESS, RELEASE }

    /** Một thao tác chuột. x, y chỉ có nghĩa với MOVE. */
    public record Op(Kind kind, int x, int y) {
        static Op move(Point p) { return new Op(Kind.MOVE, p.x, p.y); }
        static Op press() { return new Op(Kind.PRESS, 0, 0); }
        static Op release() { return new Op(Kind.RELEASE, 0, 0); }
    }

    private boolean pressed;
    private int activeId;

    public synchronized List<Op> onTouch(String action, int id, Point p) {
        if (action == null || p == null) return List.of();
        switch (action) {
            case "down":
                if (pressed) return List.of();
                pressed = true;
                activeId = id;
                return List.of(Op.move(p), Op.press());
            case "move":
                if (!pressed || id != activeId) return List.of();
                return List.of(Op.move(p));
            case "up":
                if (!pressed || id != activeId) return List.of();
                pressed = false;
                return List.of(Op.move(p), Op.release());
            default:
                return List.of();
        }
    }

    /** Thả chuột nếu đang nhấn (mất kết nối, đóng phiên, hoặc quá lâu không có tin chạm). */
    public synchronized List<Op> releaseAll() {
        if (!pressed) return List.of();
        pressed = false;
        return List.of(Op.release());
    }

    public synchronized boolean isPressed() {
        return pressed;
    }
}
