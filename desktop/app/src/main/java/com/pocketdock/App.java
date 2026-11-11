package com.pocketdock;

public class App {
    public static void main(String[] args) {
        if (SelfTest.requested(args)) {
            int code = SelfTest.run();
            RtcRuntime.shutdown();
            System.exit(code);
            return;
        }
        javafx.application.Application.launch(PocketDockApp.class, args);
    }
}
