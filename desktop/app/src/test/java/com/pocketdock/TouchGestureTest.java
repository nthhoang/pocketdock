package com.pocketdock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.util.List;
import org.junit.jupiter.api.Test;

class TouchGestureTest {

    private static TouchGesture.Op move(int x, int y) {
        return new TouchGesture.Op(TouchGesture.Kind.MOVE, x, y);
    }

    private static final TouchGesture.Op PRESS = new TouchGesture.Op(TouchGesture.Kind.PRESS, 0, 0);
    private static final TouchGesture.Op RELEASE = new TouchGesture.Op(TouchGesture.Kind.RELEASE, 0, 0);

    @Test
    void chamRoiKeoRoiNha() {
        TouchGesture g = new TouchGesture();
        assertEquals(List.of(move(10, 20), PRESS), g.onTouch("down", 1, new Point(10, 20)));
        assertEquals(List.of(move(30, 40)), g.onTouch("move", 1, new Point(30, 40)));
        assertEquals(List.of(move(50, 60), RELEASE), g.onTouch("up", 1, new Point(50, 60)));
        assertFalse(g.isPressed());
    }

    @Test
    void ngonThuHaiBiBoQua() {
        TouchGesture g = new TouchGesture();
        g.onTouch("down", 1, new Point(1, 1));
        assertTrue(g.onTouch("down", 2, new Point(5, 5)).isEmpty());
        assertTrue(g.onTouch("move", 2, new Point(6, 6)).isEmpty());
        assertTrue(g.onTouch("up", 2, new Point(7, 7)).isEmpty());
        assertTrue(g.isPressed());
        assertEquals(List.of(move(2, 2)), g.onTouch("move", 1, new Point(2, 2)));
    }

    @Test
    void diChuyenKhongNhanThiBoQua() {
        TouchGesture g = new TouchGesture();
        assertTrue(g.onTouch("move", 1, new Point(1, 1)).isEmpty());
        assertTrue(g.onTouch("up", 1, new Point(1, 1)).isEmpty());
    }

    @Test
    void hanhDongLaHoacRongThiBoQua() {
        TouchGesture g = new TouchGesture();
        assertTrue(g.onTouch("zoom", 1, new Point(1, 1)).isEmpty());
        assertTrue(g.onTouch(null, 1, new Point(1, 1)).isEmpty());
        assertTrue(g.onTouch("down", 1, null).isEmpty());
        assertFalse(g.isPressed());
    }

    @Test
    void thaTatCaKhiDangNhan() {
        TouchGesture g = new TouchGesture();
        assertTrue(g.releaseAll().isEmpty());
        g.onTouch("down", 1, new Point(1, 1));
        assertEquals(List.of(RELEASE), g.releaseAll());
        assertFalse(g.isPressed());
        assertTrue(g.releaseAll().isEmpty());
    }

    @Test
    void nhanLaiSauKhiNha() {
        TouchGesture g = new TouchGesture();
        g.onTouch("down", 1, new Point(1, 1));
        g.onTouch("up", 1, new Point(1, 1));
        assertEquals(List.of(move(9, 9), PRESS), g.onTouch("down", 2, new Point(9, 9)));
    }
}
