package com.collect.platform.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次性 CDP 探测通道：读 cookie / 执行一小段脚本，用完即关。
 * 不启动画面推流，避免和采集会话抢 screencast。
 */
public class CdpProbe implements AutoCloseable {

    private final WebSocket ws;
    private final ObjectMapper mapper;
    private final Listener listener;
    private final AtomicLong seq = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private CdpProbe(WebSocket ws, ObjectMapper mapper, Listener listener) {
        this.ws = ws;
        this.mapper = mapper;
        this.listener = listener;
    }

    public static CdpProbe open(String wsUrl, ObjectMapper mapper) throws Exception {
        Listener listener = new Listener();
        WebSocket socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .buildAsync(URI.create(wsUrl), listener)
                .join();
        CdpProbe probe = new CdpProbe(socket, mapper, listener);
        listener.owner = probe;
        return probe;
    }

    public JsonNode call(String method, Map<String, Object> params) throws Exception {
        return call(method, params, 6000);
    }

    public JsonNode call(String method, Map<String, Object> params, long timeoutMs) throws Exception {
        long id = seq.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) {
            msg.put("params", params);
        }
        ws.sendText(mapper.writeValueAsString(msg), true).join();
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            pending.remove(id);
            throw new IllegalStateException(method + " 超时");
        }
    }

    @Override
    public void close() {
        pending.values().forEach(f -> f.complete(null));
        pending.clear();
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        } catch (Exception ex) {
            ws.abort();
        }
    }

    private void onMessage(String raw) {
        JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (Exception ex) {
            return;
        }
        if (node.has("id")) {
            CompletableFuture<JsonNode> future = pending.remove(node.path("id").asLong());
            if (future != null) {
                future.complete(node);
            }
        }
    }

    private static class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();
        private volatile CdpProbe owner;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String msg = buffer.toString();
                buffer.setLength(0);
                CdpProbe probe = owner;
                if (probe != null) {
                    probe.onMessage(msg);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            // ignore
        }
    }
}
