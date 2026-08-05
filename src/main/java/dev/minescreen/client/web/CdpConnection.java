package dev.minescreen.client.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Minimal asynchronous Chrome DevTools Protocol transport over a loopback WebSocket. */
final class CdpConnection implements WebSocket.Listener, AutoCloseable {
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final StringBuilder text = new StringBuilder();
    private final BiConsumer<String, JsonObject> eventHandler;
    private final CompletableFuture<WebSocket> opened;
    private volatile WebSocket socket;
    private volatile boolean closed;

    CdpConnection(URI endpoint, BiConsumer<String, JsonObject> eventHandler) {
        this.eventHandler = eventHandler;
        opened = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .buildAsync(endpoint, this);
        opened.whenComplete((value, failure) -> {
            if (failure != null) {
                failAll(failure);
            } else {
                socket = value;
            }
        });
    }

    CompletableFuture<JsonObject> send(String method, JsonObject parameters) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("browser connection closed"));
        }
        long id = ids.incrementAndGet();
        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        message.addProperty("method", method);
        message.add("params", parameters == null ? new JsonObject() : parameters);
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        pending.put(id, result);
        opened.thenCompose(webSocket -> webSocket.sendText(message.toString(), true))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        CompletableFuture<JsonObject> removed = pending.remove(id);
                        if (removed != null) removed.completeExceptionally(failure);
                    }
                });
        result.orTimeout(8L, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((value, failure) -> pending.remove(id, result));
        return result;
    }

    CompletableFuture<JsonObject> send(String method) {
        return send(method, new JsonObject());
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        socket = webSocket;
        webSocket.request(1L);
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data,
            boolean last) {
        synchronized (text) {
            text.append(data);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                handle(message);
            }
        }
        webSocket.request(1L);
        return null;
    }

    private void handle(String value) {
        try {
            JsonObject message = JsonParser.parseString(value).getAsJsonObject();
            if (message.has("id")) {
                CompletableFuture<JsonObject> future = pending.remove(message.get("id").getAsLong());
                if (future != null) {
                    if (message.has("error")) {
                        future.completeExceptionally(new IllegalStateException(
                                message.getAsJsonObject("error").toString()));
                    } else {
                        future.complete(message.has("result")
                                ? message.getAsJsonObject("result") : new JsonObject());
                    }
                }
                return;
            }
            if (message.has("method") && eventHandler != null) {
                eventHandler.accept(message.get("method").getAsString(),
                        message.has("params") ? message.getAsJsonObject("params") : new JsonObject());
            }
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        failAll(error);
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode,
            String reason) {
        closed = true;
        failAll(new IllegalStateException("browser connection closed: " + statusCode + " " + reason));
        return null;
    }

    private void failAll(Throwable failure) {
        pending.values().forEach(future -> future.completeExceptionally(failure));
        pending.clear();
    }

    @Override
    public void close() {
        closed = true;
        WebSocket current = socket;
        if (current != null) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "MineScreen closing");
        }
        failAll(new IllegalStateException("browser connection closed"));
    }
}
