package com.pocketdock;

import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Điều khiển chuột thật bằng java.awt.Robot theo tọa độ chạm từ điện thoại.
 * Mặc định chỉ bật trên Windows. Biến môi trường POCKETDOCK_MOUSE: "real" ép bật, "off" hoặc "test" ép tắt.
 * Mọi thao tác Robot chạy trên một luồng riêng để không làm đứng giao diện.
 * Robot không điều khiển được cửa sổ đang chạy bằng quyền quản trị.
 */
public final class MouseController {

    private static final long STUCK_NS = 3_000_000_000L; // quá 3 giây không có tin chạm thì thả chuột
    private static final int MAX_ERRORS_LOGGED = 5;

    private final Robot robot;
    private final TouchGesture gesture = new TouchGesture();
    private final ScheduledExecutorService executor;
    private volatile long lastEventNs = System.nanoTime();
    private volatile boolean closed;
    private int errorsLogged;

    private MouseController(Robot robot) {
        this.robot = robot;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mouse-robot");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::watchdog, 500, 500, TimeUnit.MILLISECONDS);
    }

    public static boolean enabled() {
        String mode = System.getenv("POCKETDOCK_MOUSE");
        if ("off".equalsIgnoreCase(mode) || "test".equalsIgnoreCase(mode)) return false;
        if ("real".equalsIgnoreCase(mode)) return true;
        return SystemCheck.isWindows();
    }

    /** Trả về null nếu không bật hoặc không tạo được Robot (khi đó chỉ ghi log). */
    public static MouseController tryCreate() {
        if (!enabled()) return null;
        try {
            if (GraphicsEnvironment.isHeadless()) {
                EventLog.add("Chuot: moi truong khong co giao dien (headless), khong dieu khien chuot that");
                return null;
            }
            Robot robot = new Robot();
            robot.setAutoDelay(0);
            return new MouseController(robot);
        } catch (Throwable t) {
            EventLog.add("Chuot: khong tao duoc Robot (" + t + ")");
            return null;
        }
    }

    /** Vùng logic của màn hình chính theo AWT, cùng hệ tọa độ với Robot (cách làm đã đo ở W1). */
    public TouchMapper.Bounds bounds() {
        Rectangle b = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        return new TouchMapper.Bounds(b.x, b.y, b.width, b.height);
    }

    public void handle(String action, int id, Point p) {
        if (closed) return;
        lastEventNs = System.nanoTime();
        submit(gesture.onTouch(action, id, p));
    }

    public void releaseAll() {
        submit(gesture.releaseAll());
    }

    public void close() {
        if (closed) return;
        releaseAll();
        closed = true;
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void watchdog() {
        if (gesture.isPressed() && System.nanoTime() - lastEventNs > STUCK_NS) {
            EventLog.add("Chuot: qua 3 giay khong co tin cham, tha chuot");
            for (TouchGesture.Op op : gesture.releaseAll()) apply(op);
        }
    }

    private void submit(List<TouchGesture.Op> ops) {
        if (ops.isEmpty()) return;
        try {
            executor.execute(() -> {
                for (TouchGesture.Op op : ops) apply(op);
            });
        } catch (RejectedExecutionException ignored) {
            // da dong
        }
    }

    private void apply(TouchGesture.Op op) {
        try {
            switch (op.kind()) {
                case MOVE -> robot.mouseMove(op.x(), op.y());
                case PRESS -> robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                case RELEASE -> robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            }
        } catch (Throwable t) {
            if (errorsLogged < MAX_ERRORS_LOGGED) {
                errorsLogged++;
                EventLog.add("Chuot: loi Robot khi " + op.kind() + ": " + t);
            }
        }
    }
}
