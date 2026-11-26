package com.collect.platform.cdp;

import com.collect.platform.video.VideoIngestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * CDP 页面的网络响应解析与入库桥接：
 * 监听 /aweme/ 接口 → 提取作品 → 判定 AI 短剧口径 → 入库并汇总计数。
 * 从 CdpSession 拆出，避免单个类同时承担连接、推流和入库三件事。
 */
class CdpIngestBridge {

    private final VideoIngestService ingest;
    private final ObjectMapper mapper;
    private final String collector;
    private final String terminalCode;
    private final Map<String, String> pendingResponses = new ConcurrentHashMap<>();

    private volatile String activeKeyword;
    private volatile int ingestedTotal;
    private volatile int scannedTotal;
    private volatile int mixHitTotal;
    private volatile int aiHitTotal;
    private volatile int droppedTotal;

    CdpIngestBridge(VideoIngestService ingest, ObjectMapper mapper, String collector, String terminalCode) {
        this.ingest = ingest;
        this.mapper = mapper;
        this.collector = collector;
        this.terminalCode = terminalCode;
    }

    void responseReceived(String requestId, String url) {
        pendingResponses.put(requestId, url);
    }

    /**
     * 请求完成时调用：是 /aweme/ 接口且处于监听状态才返回 URL，调用方据此拉取响应体。
     */
    String takeAwemeUrl(String requestId, boolean listening) {
        String url = pendingResponses.remove(requestId);
        if (url == null || !listening || !url.contains("/aweme/")) {
            return null;
        }
        return url;
    }

    /** 当前搜索结果页对应的关键词；入库时写入 videos.search_keyword，供周分析归因。 */
    String activeKeyword() {
        return activeKeyword;
    }

    int ingestedTotal() {
        return ingestedTotal;
    }

    Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ingested", ingestedTotal);
        out.put("scanned", scannedTotal);
        out.put("mixHits", mixHitTotal);
        out.put("aiHits", aiHitTotal);
        out.put("dropped", droppedTotal);
        return out;
    }

    /** 从搜索结果页 URL 里取关键词（/search/xxx），离开搜索页则清空。 */
    void syncSearchKeyword(String url) {
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

    /** 解析 Network.getResponseBody 的返回并入库。 */
    void handleBody(JsonNode response, String url, Consumer<Map<String, Object>> emit) {
        JsonNode result = response.path("result");
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
        handleJsonBody(body, url, emit);
    }

    private void handleJsonBody(String body, String url, Consumer<Map<String, Object>> emit) {
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
            emit.accept(Map.of("type", "ingested", "count", 0, "inserted", 0, "total", ingestedTotal,
                    "scanned", found.size(), "mixHits", mixHitTotal, "aiHits", aiHitTotal,
                    "dropped", droppedTotal, "url", url));
            return;
        }
        int inserted;
        try {
            inserted = ingest.save(qualified, collector, url, terminalCode, activeKeyword);
        } catch (Exception ex) {
            emit.accept(Map.of("type", "error", "message", "入库失败：" + ex.getMessage()));
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
        emit.accept(msg);
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
}
