package com.collect.platform.cdp;

import com.collect.platform.video.VideoIngestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 一条「平台前端 <- 本机 Chrome 页面」的会话：
 *  - Page.startScreencast 画面推给前端
 *  - 前端鼠标/键盘事件回放到页面
 *  - 监听 XHR/Fetch 的 JSON 响应，把 aweme 列表实时入库
 */
public class CdpSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CdpSession.class);
    private static final long FRAME_INTERVAL_MS = 80;
    private static final java.util.concurrent.ScheduledExecutorService SCHED =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cdp-screencast");
                t.setDaemon(true);
                return t;
            });

    private final String pageWsUrl;
    private final String collector;
    private final String terminalCode;
    private final VideoIngestService ingest;
    private final ObjectMapper mapper;
    /** 前端出口可换绑：同一槽位的前端重连时直接挂到已有会话上，采集状态不归零。 */
    private volatile Consumer<String> sink;
    private volatile boolean attached;
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicLong cmdId = new AtomicLong();
    private final Map<Long, Consumer<JsonNode>> callbacks = new ConcurrentHashMap<>();
    private final Map<String, String> pendingResponses = new ConcurrentHashMap<>();

    private volatile WebSocket ws;
    private volatile boolean listening;
    private volatile boolean closed;
    private volatile long lastFrameAt;
    /** 最近一次重启推流的时间，防止看门狗反复重启。 */
    private volatile long lastRestartAt;
    /** 兜底截图的时间戳：截图请求没回来时用来解开 shotBusy，避免画面永久卡死。 */
    private volatile long shotAt;
    private volatile int deviceWidth = 1280;
    private volatile int deviceHeight = 800;
    private volatile String currentUrl = "";
    /** 当前搜索结果页对应的关键词；入库时写入 videos.search_keyword，供周分析归因 */
    private volatile String activeKeyword;
    private volatile int ingestedTotal;
    /** 采集口径统计：扫到的作品数、合集命中、AI 命中、双命中入库。 */
    private volatile int scannedTotal;
    private volatile int mixHitTotal;
    private volatile int aiHitTotal;
    private volatile int droppedTotal;
    private volatile boolean shotBusy;
    private volatile boolean shotLoopOn;
    /** 通道失效回调：由槽位管理器踢掉前端连接，让浏览器重连后重建会话，避免一直「已连接但黑屏」。 */
    private volatile Runnable onDead;
    private int sendFailures;
    private int shotTimeouts;

    public CdpSession(String pageWsUrl, String collector, String terminalCode,
                      VideoIngestService ingest, ObjectMapper mapper, Consumer<String> sink) {
        this.pageWsUrl = pageWsUrl;
        this.collector = collector;
        this.terminalCode = terminalCode;
        this.ingest = ingest;
        this.mapper = mapper;
        this.sink = sink;
        this.attached = sink != null;
    }

    public void setOnDead(Runnable callback) {
        this.onDead = callback;
    }

    /** 前端重连时把画面出口换到新连接，采集会话本身继续跑。 */
    public void attach(Consumer<String> newSink) {
        boolean wasDetached = !attached || sink == null;
        this.sink = newSink;
        this.attached = newSink != null;
        if (newSink != null && wasDetached) {
            // 之前没人看的时候 Chrome 可能已经把推流停了，重新挂上时重开一次
            restartScreencast();
        }
    }

    /** 前端断开但保留采集：不再推画面，不再丢日志。 */
    public void detach() {
        this.attached = false;
        this.sink = null;
    }

    public boolean isAlive() {
        WebSocket socket = ws;
        return !closed && socket != null && !socket.isOutputClosed();
    }

    public int getIngestedTotal() {
        return ingestedTotal;
    }

    /** 采集口径计数，前端重连后回填用。 */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ingested", ingestedTotal);
        out.put("scanned", scannedTotal);
        out.put("mixHits", mixHitTotal);
        out.put("aiHits", aiHitTotal);
        out.put("dropped", droppedTotal);
        return out;
    }

    public void connect() {
        try {
            ws = http.newWebSocketBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(8))
                    .buildAsync(URI.create(pageWsUrl), new ChromeListener())
                    .join();
            send("Page.enable", null);
            send("Network.enable", Map.of("maxTotalBufferSize", 50 * 1024 * 1024));
            send("Runtime.enable", null);
            restartScreencast();
            startShotFallback();
            startFrameWatchdog();
            emitState();
        } catch (Exception ex) {
            throw new IllegalStateException("连接页面调试通道失败：" + ex.getMessage(), ex);
        }
    }

    public boolean isListening() {
        return listening;
    }

    public String getCurrentUrl() {
        return currentUrl;
    }

    /* ================= 前端消息入口 ================= */

    public void onClientMessage(JsonNode msg) {
        String type = msg.path("type").asText("");
        switch (type) {
            case "listening" -> {
                listening = msg.path("on").asBoolean(false);
                emitState();
                emit(Map.of("type", "toast",
                        "message", listening ? "已开始监听采集" : "已停止监听"));
                // 重新开启时，让 Network 域重发一次缓存响应，避免错过页面停留数据
                if (listening) {
                    send("Network.setCacheDisabled", Map.of("cacheDisabled", false));
                }
            }
            case "mouse" -> dispatchMouse(msg);
            case "wheel" -> send("Input.dispatchMouseEvent", Map.of(
                    "type", "mouseWheel",
                    "x", msg.path("x").asDouble(),
                    "y", msg.path("y").asDouble(),
                    "deltaX", msg.path("deltaX").asDouble(0),
                    "deltaY", msg.path("deltaY").asDouble(0)));
            case "key" -> dispatchKey(msg);
            case "navigate" -> {
                String url = msg.path("url").asText("https://www.douyin.com");
                syncSearchKeyword(url);
                send("Page.navigate", Map.of("url", url));
            }
            case "reload" -> send("Page.reload", Map.of("ignoreCache", false));
            case "eval" -> executeScript(msg.path("expression").asText(""));
            case "resize" -> send("Emulation.setDeviceMetricsOverride", Map.of(
                    "width", Math.max(320, msg.path("width").asInt(1280)),
                    "height", Math.max(320, msg.path("height").asInt(800)),
                    "deviceScaleFactor", 1, "mobile", false));
            default -> { }
        }
    }

    private void dispatchMouse(JsonNode msg) {
        String event = msg.path("event").asText("moved");
        String type = switch (event) {
            case "pressed" -> "mousePressed";
            case "released" -> "mouseReleased";
            default -> "mouseMoved";
        };
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", type);
        params.put("x", msg.path("x").asDouble());
        params.put("y", msg.path("y").asDouble());
        params.put("button", msg.path("button").asText("left"));
        if (!"mouseMoved".equals(type)) {
            params.put("buttons", "pressed".equals(event) ? 1 : 0);
            params.put("clickCount", msg.path("clickCount").asInt(1));
        }
        send("Input.dispatchMouseEvent", params);
    }

    private void executeScript(String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        long id = cmdId.incrementAndGet();
        callbacks.put(id, resp -> {
            JsonNode result = resp.path("result").path("result");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "eval");
            out.put("ok", !resp.has("error"));
            out.put("value", result.path("value").asText(""));
            if (resp.has("error")) {
                out.put("message", resp.path("error").path("message").asText(""));
            }
            emit(out);
        });
        write(Map.of("id", id, "method", "Runtime.evaluate",
                "params", Map.of("expression", expression, "returnByValue", true)));
    }

    private void dispatchKey(JsonNode msg) {
        String event = msg.path("event").asText("keyDown");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", switch (event) {
            case "keyUp" -> "keyUp";
            case "char" -> "char";
            default -> "keyDown";
        });
        params.put("key", msg.path("key").asText(""));
        params.put("code", msg.path("code").asText(""));
        params.put("windowsVirtualKeyCode", msg.path("keyCode").asInt(0));
        params.put("nativeVirtualKeyCode", msg.path("keyCode").asInt(0));
        params.put("modifiers", msg.path("modifiers").asInt(0));
        if ("char".equals(event)) {
            params.put("text", msg.path("text").asText(""));
            params.put("unmodifiedText", msg.path("text").asText(""));
        }
        send("Input.dispatchKeyEvent", params);
    }

    /* ================= Chrome 消息处理 ================= */

    private void handleChrome(String raw) {
        JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (Exception ex) {
            return;
        }
        if (node.has("id")) {
            Consumer<JsonNode> cb = callbacks.remove(node.path("id").asLong());
            if (cb != null) {
                cb.accept(node);
            }
            return;
        }
        String method = node.path("method").asText("");
        JsonNode params = node.path("params");
        switch (method) {
            case "Page.screencastFrame" -> onFrame(params);
            case "Page.frameNavigated" -> {
                if (params.path("frame").path("parentId").isMissingNode()) {
                    currentUrl = params.path("frame").path("url").asText("");
                    syncSearchKeyword(currentUrl);
                    emitState();
                    // 页面导航后 Chrome 会停掉 screencast，必须重新开，否则画面卡在上一页
                    restartScreencast();
                }
            }
            case "Network.responseReceived" -> {
                String type = params.path("type").asText("");
                if ("XHR".equals(type) || "Fetch".equals(type)) {
                    pendingResponses.put(params.path("requestId").asText(),
                            params.path("response").path("url").asText(""));
                }
            }
            case "Network.loadingFinished" -> onLoadingFinished(params.path("requestId").asText());
            default -> { }
        }
    }

    /** 从搜索结果页 URL 里取关键词（/search/xxx），离开搜索页则清空。 */
    private void syncSearchKeyword(String url) {
        if (url == null || !url.contains("/search/")) {
            activeKeyword = null;
            return;
        }
        int i = url.indexOf("/search/");
        String rest = url.substring(i + 8);
        int q = rest.indexOf('?');
        if (q >= 0) {
            rest = rest.substring(0, q);
        }
        try {
            rest = java.net.URLDecoder.decode(rest, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            /* 保持原样 */
        }
        rest = rest.trim();
        activeKeyword = rest.isEmpty() ? null : (rest.length() > 128 ? rest.substring(0, 128) : rest);
    }

    private void restartScreencast() {
        lastFrameAt = 0;
        lastRestartAt = System.currentTimeMillis();
        // 上一个会话可能把 screencast 留在开启态；先停、隔一会儿再开，
        // 否则 Chrome 会忽略新的 start，前端一直黑屏。
        send("Page.stopScreencast", null);
        SCHED.schedule(() -> {
            if (closed) {
                return;
            }
            send("Page.startScreencast", Map.of(
                    "format", "jpeg", "quality", 65,
                    "maxWidth", 1280, "maxHeight", 800, "everyNthFrame", 1));
        }, 300, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 画面看门狗：有人看但 8 秒没有新帧，说明 Chrome 那边的推流已经哑了，
     * 重启一次；两次重启之间至少隔 15 秒，避免来回抖。
     */
    private void startFrameWatchdog() {
        SCHED.scheduleWithFixedDelay(() -> {
            if (closed || !attached) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastFrameAt > 8000 && now - lastRestartAt > 15000) {
                log.info("{} 8 秒无画面，重启推流", terminalCode);
                restartScreencast();
            }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Chrome 窗口被最小化/遮挡时 document.visibilityState = hidden，
     * screencast 会被暂停且不再推帧（帧列表里 visible:false），前端一直黑屏。
     * 这里在 screencast 静默时用 captureScreenshot 兜底拉帧，保证任何状态下都有画面。
     */
    private void startShotFallback() {
        if (shotLoopOn) {
            return;
        }
        shotLoopOn = true;
        SCHED.scheduleWithFixedDelay(() -> {
            try {
                pollScreenshot();
            } catch (Exception ex) {
                log.debug("兜底截图失败: {}", ex.getMessage());
            }
        }, 1500, 800, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void pollScreenshot() {
        if (closed) {
            shotLoopOn = false;
            return;
        }
        long now0 = System.currentTimeMillis();
        // 上一次截图请求丢了响应时把 busy 解开，否则兜底永远不再发新图
        if (shotBusy && now0 - shotAt > 5000) {
            shotBusy = false;
            if (++shotTimeouts >= 3) {
                markDead("兜底截图连续超时");
                return;
            }
        }
        if (shotBusy || now0 - lastFrameAt < 1500) {
            return;
        }
        shotBusy = true;
        shotAt = now0;
        long id = cmdId.incrementAndGet();
        callbacks.put(id, resp -> {
            shotBusy = false;
            String data = resp.path("result").path("data").asText("");
            if (data.isEmpty() || closed) {
                return;
            }
            shotTimeouts = 0;
            long now = System.currentTimeMillis();
            if (now - lastFrameAt < FRAME_INTERVAL_MS) {
                return;
            }
            lastFrameAt = now;
            emit(Map.of("type", "frame", "data", data,
                    "w", deviceWidth, "h", deviceHeight));
        });
        write(Map.of("id", id, "method", "Page.captureScreenshot",
                "params", Map.of("format", "jpeg", "quality", 60)));
    }

    private void onFrame(JsonNode params) {
        long sessionId = params.path("sessionId").asLong();
        send("Page.screencastFrameAck", Map.of("sessionId", sessionId));
        int w = params.path("metadata").path("deviceWidth").asInt(deviceWidth);
        int h = params.path("metadata").path("deviceHeight").asInt(deviceHeight);
        deviceWidth = w;
        deviceHeight = h;
        long now = System.currentTimeMillis();
        if (now - lastFrameAt < FRAME_INTERVAL_MS) {
            return;
        }
        lastFrameAt = now;
        emit(Map.of("type", "frame", "data", params.path("data").asText(""),
                "w", w, "h", h));
    }

    private void onLoadingFinished(String requestId) {
        String url = pendingResponses.remove(requestId);
        if (url == null || !listening || closed) {
            return;
        }
        if (!url.contains("/aweme/")) {
            return;
        }
        long id = cmdId.incrementAndGet();
        callbacks.put(id, resp -> {
            JsonNode result = resp.path("result");
            String body = result.path("body").asText("");
            if (body.isEmpty()) {
                return;
            }
            if (result.path("base64Encoded").asBoolean(false)) {
                try {
                    body = new String(java.util.Base64.getDecoder().decode(body),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception ex) {
                    return;
                }
            }
            handleJsonBody(body, url);
        });
        write(Map.of("id", id, "method", "Network.getResponseBody",
                "params", Map.of("requestId", requestId)));
    }

    private void handleJsonBody(String body, String url) {
        if (!body.startsWith("{") && !body.startsWith("[")) {
            return;
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception ex) {
            return;
        }
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        collectAwemes(root, found, 0);
        if (found.isEmpty()) {
            return;
        }

        // v1.4.0 口径：合集 + AI 声明双命中才入库，其余只计数用于观察采集质量
        List<Map<String, Object>> qualified = new ArrayList<>();
        int mixHits = 0;
        int aiHits = 0;
        for (Map<String, Object> item : found.values()) {
            VideoIngestService.Judgement j = ingest.judge(item);
            if (j.hasMix()) {
                mixHits++;
            }
            if (j.hasAi()) {
                aiHits++;
            }
            if (j.qualified()) {
                qualified.add(item);
            }
        }
        scannedTotal += found.size();
        mixHitTotal += mixHits;
        aiHitTotal += aiHits;
        droppedTotal += found.size() - qualified.size();
        if (qualified.isEmpty()) {
            emit(Map.of("type", "ingested", "count", 0, "inserted", 0, "total", ingestedTotal,
                    "scanned", found.size(), "mixHits", mixHitTotal, "aiHits", aiHitTotal,
                    "dropped", droppedTotal, "url", url));
            return;
        }
        int inserted = 0;
        try {
            inserted = ingest.save(qualified, collector, url, terminalCode, activeKeyword);
        } catch (Exception ex) {
            log.warn("视频入库失败: {}", ex.getMessage());
            emit(Map.of("type", "error", "message", "入库失败：" + ex.getMessage()));
            return;
        }
        ingestedTotal += inserted;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "ingested");
        msg.put("count", qualified.size());
        msg.put("inserted", inserted);
        msg.put("total", ingestedTotal);
        msg.put("scanned", found.size());
        msg.put("mixHits", mixHitTotal);
        msg.put("aiHits", aiHitTotal);
        msg.put("dropped", droppedTotal);
        msg.put("url", url);
        emit(msg);
    }

    @SuppressWarnings("unchecked")
    private void collectAwemes(JsonNode node, Map<String, Map<String, Object>> out, int depth) {
        if (depth > 8 || node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode awemeId = node.get("aweme_id");
            JsonNode author = node.get("author");
            if (awemeId != null && author != null && author.isObject()
                    && author.has("sec_uid")) {
                String id = awemeId.asText();
                if (!id.isEmpty() && !out.containsKey(id) && out.size() < 2000) {
                    out.put(id, mapper.convertValue(node, Map.class));
                }
            }
            node.fields().forEachRemaining(e -> collectAwemes(e.getValue(), out, depth + 1));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectAwemes(child, out, depth + 1);
            }
        }
    }

    /* ================= 基础收发 ================= */

    private void send(String method, Map<String, Object> params) {
        long id = cmdId.incrementAndGet();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) {
            msg.put("params", params);
        }
        write(msg);
    }

    private void write(Map<String, Object> msg) {
        WebSocket socket = ws;
        if (socket == null || closed) {
            return;
        }
        try {
            socket.sendText(mapper.writeValueAsString(msg), true).join();
            sendFailures = 0;
        } catch (Exception ex) {
            if (++sendFailures >= 3) {
                markDead("连续发送失败：" + ex.getMessage());
            }
            log.debug("CDP 发送失败 {}: {}", msg.get("method"), ex.getMessage());
        }
    }

    private void emitState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "state");
        state.put("listening", listening);
        state.put("url", currentUrl);
        state.put("width", deviceWidth);
        state.put("height", deviceHeight);
        emit(state);
    }

    private void emit(Map<String, Object> msg) {
        Consumer<String> out = sink;
        if (closed || !attached || out == null) {
            return;
        }
        try {
            out.accept(mapper.writeValueAsString(msg));
        } catch (Exception ex) {
            log.debug("转发前端失败: {}", ex.getMessage());
        }
    }

    @Override
    public void close() {
        closed = true;
        WebSocket socket = ws;
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ex) {
                socket.abort();
            }
        }
    }

    /** 通道已死：标记关闭并通知槽位管理器重建，否则前端会一直显示「已连接」却收不到任何画面。 */
    private void markDead(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        log.warn("{} 采集通道失效：{}", terminalCode, reason);
        Runnable callback = onDead;
        if (callback != null) {
            try {
                callback.run();
            } catch (Exception ignore) {
                // 回调失败不影响后续重建
            }
        }
    }

    private class ChromeListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

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
                try {
                    handleChrome(msg);
                } catch (Exception ex) {
                    log.debug("处理 CDP 消息失败: {}", ex.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            markDead("页面调试通道已断开：" + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            markDead("CDP 通道异常：" + error.getMessage());
        }
    }
}
