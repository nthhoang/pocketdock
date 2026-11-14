package com.pocketdock;

public class App {
    public static void main(String[] args) {
        if (SelfTest.requested(args)) {
            int code = SelfTest.run();
            RtcRuntime.shutdown();
            System.exit(code);
            return;
        }
        if (AutoStack.requested(args)) {
            try {
                AutoStack.start();
            } catch (Exception e) {
                String msg = "Khong tu dong bat duoc may chu va duong ham: " + e.getMessage();
                System.err.println(Ascii.fold(msg));
                EventLog.add(msg);
                AutoStack.stop();
            }
        }
        javafx.application.Application.launch(PocketDockApp.class, args);
    }
}
