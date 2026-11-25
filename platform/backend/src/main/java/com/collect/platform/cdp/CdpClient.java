package com.collect.platform.cdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机 Chrome 的 CDP 发现接口（/json/version、/json/list、/json/new）。
 */
@Component
public class CdpClient {

    private static final Logger log = LoggerFactory.getLogger(CdpClient.class);

    private final String base;
    /** 槽位绑定过的标签页 targetId：base|slot → targetId。 */
    private static final Map<String, String> SLOT_TARGETS = new java.util.concurrent.ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public CdpClient(@Value("${app.cdp.host:host.docker.internal}") String host,
                     @Value("${app.cdp.port:9222}") int port,
                     ObjectMapper objectMapper) {
        this.base = "http://" + host + ":" + port;
        this.objectMapper = objectMapper;
    }

    public String baseUrl() {
        return base;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpoint", base);
        try {
            JsonNode version = get("/json/version");
            out.put("available", true);
            out.put("browser", version.path("Browser").asText(null));
            List<Map<String, Object>> pages = new ArrayList<>();
            for (JsonNode p : get("/json/list")) {
                if (!"page".equals(p.path("type").asText())) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", p.path("id").asText());
                item.put("url", p.path("url").asText());
                item.put("title", p.path("title").asText());
                item.put("ws", p.path("webSocketDebuggerUrl").asText());
                pages.add(item);
            }
            out.put("pages", pages);
            out.put("target", pickDouyin(pages));
        } catch (Exception ex) {
            out.put("available", false);
            out.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            out.put("hint", "未连到本机 Chrome 的 9222。请先运行桌面上的「启动抖音采集窗口」快捷方式（远程调试独立 profile）。");
        }
        return out;
    }

    /** 优先复用已打开的抖音页，其次新建一个。 */
    public String ensureDouyinPage(String url) {
        try {
            List<Map<String, Object>> pages = listPages();
            Object picked = pickDouyin(pages);
            if (picked instanceof Map<?, ?> m) {
                return rewriteWs(String.valueOf(m.get("ws")));
            }
            return openTab(url);
        } catch (Exception ex) {
            throw new IllegalStateException("CDP 不可用：" + ex.getMessage(), ex);
        }
    }

    /**
     * 按槽位（feed / search）复用标签页，两条采集线各占一个独立 target，互不抢页面。
     * 记住每个槽位绑定过的 targetId，页面被导航到别处也能继续绑回同一个标签。
     */
    public String ensureDouyinPage(String url, String slot) {
        if (slot == null || slot.isBlank()) {
            return ensureDouyinPage(url);
        }
        String key = base + "|" + slot;
        try {
            List<Map<String, Object>> pages = listPages();
            String bound = SLOT_TARGETS.get(key);
            if (bound != null) {
                for (Map<String, Object> p : pages) {
                    if (bound.equals(String.valueOf(p.get("id")))) {
                        return rewriteWs(String.valueOf(p.get("ws")));
                    }
                }
                SLOT_TARGETS.remove(key);
            }
            for (Map<String, Object> p : pages) {
                if (matchesSlot(String.valueOf(p.get("url")), slot)) {
                    SLOT_TARGETS.put(key, String.valueOf(p.get("id")));
                    return rewriteWs(String.valueOf(p.get("ws")));
                }
            }
            String ws = openTab(url);
            if (ws != null && ws.lastIndexOf('/') > 0) {
                SLOT_TARGETS.put(key, ws.substring(ws.lastIndexOf('/') + 1));
            }
            return ws;
        } catch (Exception ex) {
            throw new IllegalStateException("CDP 不可用：" + ex.getMessage(), ex);
        }
    }

    private static boolean matchesSlot(String url, String slot) {
        if (url == null || !url.contains("douyin.com")) {
            return false;
        }
        return switch (slot) {
            case "search" -> url.contains("/search/");
            case "feed" -> url.contains("/jingxuan");
            default -> true;
        };
    }

    private List<Map<String, Object>> listPages() throws Exception {
        List<Map<String, Object>> pages = new ArrayList<>();
        for (JsonNode p : get("/json/list")) {
            if (!"page".equals(p.path("type").asText())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", p.path("id").asText());
            item.put("url", p.path("url").asText());
            item.put("title", p.path("title").asText());
            item.put("ws", p.path("webSocketDebuggerUrl").asText());
            pages.add(item);
        }
        return pages;
    }

    public String openTab(String url) {
        try {
            // Chrome 的 /json/new 用位置参数接收地址：/json/new?<url>，不是 /json/new?url=<url>
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/json/new?"
                            + java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("打开标签页失败 HTTP " + resp.statusCode());
            }
            JsonNode node = objectMapper.readTree(resp.body());
            return rewriteWs(node.path("webSocketDebuggerUrl").asText(null));
        } catch (Exception ex) {
            throw new IllegalStateException("打开标签页失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * Chrome 返回的调试地址永远以它自己视角的 127.0.0.1:9222 开头，
     * 平台在容器里必须换成终端登记的 host:port，否则连的是容器自己。
     */
    private String rewriteWs(String ws) {
        if (ws == null || ws.isBlank()) {
            return null;
        }
        String path = ws.replaceFirst("^wss?://[^/]+", "");
        String origin = base.replaceFirst("^https?://", "");
        return "ws://" + origin + path;
    }

    private Object pickDouyin(List<Map<String, Object>> pages) {
        for (Map<String, Object> p : pages) {
            String url = String.valueOf(p.get("url"));
            if (url.contains("douyin.com")) {
                return p;
            }
        }
        return pages.isEmpty() ? null : pages.get(0);
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException(path + " HTTP " + resp.statusCode());
        }
        return objectMapper.readTree(resp.body());
    }
}
