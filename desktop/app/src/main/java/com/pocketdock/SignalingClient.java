package com.pocketdock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Kết nối WebSocket tới máy chủ, dùng WebSocket có sẵn trong Java. */
public final class SignalingClient implements WebSocket.Listener {

    public interface Handler {
        void onOpen();
        void onMessage(JsonObject message);
        void onClosed(String reason);
        void onError(Throwable error);
    }

    private final Handler handler;
    private final HttpClient http = HttpClient.newHttpClient();
    private final StringBuilder partial = new StringBuilder();
    private volatile WebSocket socket;
    private volatile boolean closedByUs = false;
    private CompletableFuture<Object> sendChain = CompletableFuture.completedFuture(null);

    public SignalingClient(Handler handler) {
        this.handler = handler;
    }

    public void connect(URI uri) {
        http.newWebSocketBuilder().buildAsync(uri, this).whenComplete((ws, error) -> {
            if (error != null && !closedByUs) handler.onError(error);
        });
    }

    public void send(String type) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        sendText(object.toString());
    }

    public void send(JsonObject object) {
        sendText(object.toString());
    }

    private synchronized void sendText(String text) {
        WebSocket ws = socket;
        if (ws == null) return;
        // Mỗi lần chỉ được gửi một tin, nên xếp hàng nối đuôi nhau
        sendChain = sendChain
                .handle((v, e) -> (Object) null)
                .thenCompose(v -> ws.sendText(text, true).thenApply(w -> (Object) w));
    }

    public void close() {
        closedByUs = true;
        WebSocket ws = socket;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        this.socket = webSocket;
        webSocket.request(1);
        handler.onOpen();
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String text = partial.toString();
            partial.setLength(0);
            try {
                handler.onMessage(JsonParser.parseString(text).getAsJsonObject());
            } catch (RuntimeException e) {
                // tin nhắn hỏng thì bỏ qua
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (!closedByUs) handler.onClosed(reason);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (!closedByUs) handler.onError(error);
    }
}
