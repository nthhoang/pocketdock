package com.pocketdock.probe;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.DisplayMode;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MouseProbe {

    private record Hit(int button, int x, int y) {}

    public static void main(String[] args) throws Exception {
        try (Report r = new Report("ketqua-chuot.txt")) {
            run(r);
        } catch (Throwable t) {
            System.err.println("LOI: " + t);
            t.printStackTrace();
        }
        System.exit(0);
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static void run(Report r) throws Exception {
        r.line("=== THU DIEU KHIEN CHUOT (java.awt.Robot) ===");
        r.line("He dieu hanh: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        r.line("Java: " + System.getProperty("java.version"));

        if (GraphicsEnvironment.isHeadless()) {
            r.line("KET QUA: LOI - moi truong khong co giao dien (headless).");
            return;
        }

        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] devices = ge.getScreenDevices();
        r.line("So man hinh: " + devices.length);
        for (GraphicsDevice d : devices) {
            GraphicsConfiguration gc = d.getDefaultConfiguration();
            Rectangle b = gc.getBounds();
            AffineTransform tx = gc.getDefaultTransform();
            DisplayMode dm = d.getDisplayMode();
            r.line("  Man hinh " + d.getIDstring() + ": vung logic " + b.x + "," + b.y + " " + b.width + "x" + b.height
                    + ", he so phong to " + f(tx.getScaleX()) + "x" + f(tx.getScaleY())
                    + ", do phan giai bao cao " + dm.getWidth() + "x" + dm.getHeight());
        }

        GraphicsDevice primary = ge.getDefaultScreenDevice();
        GraphicsConfiguration pgc = primary.getDefaultConfiguration();
        Rectangle b = pgc.getBounds();
        r.line("Man hinh chinh: vung logic " + b.x + "," + b.y + " " + b.width + "x" + b.height
                + ", he so phong to " + f(pgc.getDefaultTransform().getScaleX()));
        r.line("");
        r.line("Trong khoang 15 giay tiep theo: KHONG cham vao chuot va ban phim.");
        Thread.sleep(3000);

        Robot robot = new Robot();
        robot.setAutoDelay(0);

        // ---- Thu 1: di chuyen den cac diem theo ti le ----
        r.line("");
        r.line("--- Thu 1: di chuyen chuot den cac diem theo ti le man hinh ---");
        double[][] points = {{0.05, 0.05}, {0.5, 0.5}, {0.95, 0.95}, {0.95, 0.05}, {0.05, 0.95}};
        int moveOk = 0;
        for (double[] p : points) {
            int x = b.x + (int) Math.round(p[0] * (b.width - 1));
            int y = b.y + (int) Math.round(p[1] * (b.height - 1));
            robot.mouseMove(x, y);
            Thread.sleep(150);
            Point actual = MouseInfo.getPointerInfo().getLocation();
            int dx = actual.x - x;
            int dy = actual.y - y;
            boolean good = Math.abs(dx) <= 1 && Math.abs(dy) <= 1;
            if (good) moveOk++;
            r.line("  ti le (" + p[0] + ", " + p[1] + "): mong muon (" + x + "," + y + "), thuc te ("
                    + actual.x + "," + actual.y + "), lech (" + dx + "," + dy + ") -> " + (good ? "OK" : "LECH"));
        }
        r.line("Ket qua di chuyen: " + moveOk + "/" + points.length + " diem dung");

        // ---- Thu 2: nhap chuot vao mot cua so co kich thuoc biet truoc ----
        r.line("");
        r.line("--- Thu 2: nhap trai, nhap phai, cuon vao mot cua so mau cam o giua man hinh ---");
        final int w = 400;
        final int h = 300;
        List<Hit> hits = new CopyOnWriteArrayList<>();
        List<Integer> wheels = new CopyOnWriteArrayList<>();
        JFrame[] frameHolder = new JFrame[1];
        JPanel[] panelHolder = new JPanel[1];

        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("PocketDock thu chuot");
            frame.setUndecorated(true);
            frame.setAlwaysOnTop(true);
            JPanel panel = new JPanel();
            panel.setBackground(Color.ORANGE);
            panel.setPreferredSize(new Dimension(w, h));
            MouseAdapter listener = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    hits.add(new Hit(e.getButton(), e.getX(), e.getY()));
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    wheels.add(e.getWheelRotation());
                }
            };
            panel.addMouseListener(listener);
            panel.addMouseWheelListener(listener);
            frame.add(panel);
            frame.pack();
            frame.setLocation(b.x + (b.width - w) / 2, b.y + (b.height - h) / 2);
            frame.setVisible(true);
            frameHolder[0] = frame;
            panelHolder[0] = panel;
        });
        Thread.sleep(1000);

        Point[] origin = new Point[1];
        SwingUtilities.invokeAndWait(() -> origin[0] = panelHolder[0].getLocationOnScreen());

        int[][] targets = {{20, 20}, {w / 2, h / 2}, {w - 20, h - 20}};
        String[] names = {"gan goc tren trai", "chinh giua", "gan goc duoi phai"};
        int clickOk = 0;
        for (int i = 0; i < targets.length; i++) {
            int tx = targets[i][0];
            int ty = targets[i][1];
            int before = hits.size();
            robot.mouseMove(origin[0].x + tx, origin[0].y + ty);
            Thread.sleep(150);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(250);
            if (hits.size() == before) {
                r.line("  Nhap trai " + names[i] + ": KHONG nhan duoc su kien nhap chuot -> LOI");
                continue;
            }
            Hit hit = hits.get(hits.size() - 1);
            int dx = hit.x() - tx;
            int dy = hit.y() - ty;
            boolean good = hit.button() == MouseEvent.BUTTON1 && Math.abs(dx) <= 2 && Math.abs(dy) <= 2;
            if (good) clickOk++;
            r.line("  Nhap trai " + names[i] + ": mong muon (" + tx + "," + ty + ") trong cua so, nhan duoc ("
                    + hit.x() + "," + hit.y() + "), lech (" + dx + "," + dy + ") -> " + (good ? "OK" : "LECH"));
        }

        // nhap phai
        boolean rightOk = false;
        int before = hits.size();
        robot.mouseMove(origin[0].x + w / 2, origin[0].y + h / 2);
        Thread.sleep(150);
        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
        Thread.sleep(250);
        if (hits.size() > before) {
            rightOk = hits.get(hits.size() - 1).button() == MouseEvent.BUTTON3;
        }
        r.line("  Nhap phai: " + (rightOk ? "OK" : "LOI - khong nhan dung nut phai"));

        // cuon
        int wheelBefore = wheels.size();
        robot.mouseWheel(3);
        Thread.sleep(250);
        boolean wheelOk = wheels.size() > wheelBefore;
        r.line("  Cuon chuot: " + (wheelOk ? "OK" : "LOI - khong nhan su kien cuon"));

        SwingUtilities.invokeAndWait(() -> frameHolder[0].dispose());

        r.line("");
        boolean all = moveOk == points.length && clickOk == targets.length && rightOk && wheelOk;
        if (all) {
            r.line("KET QUA: OK - chuot dieu khien dung o muc phong to man hinh hien tai.");
        } else {
            r.line("KET QUA: CO LOI/LECH - xem tung dong o tren. (He so phong to hien tai: "
                    + f(pgc.getDefaultTransform().getScaleX()) + ")");
        }
        r.line("Luu y: Robot thuong KHONG dieu khien duoc cua so dang chay bang quyen quan tri (vi du Task Manager).");
    }
}
