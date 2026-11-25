package com.collect.platform.cdp;

import com.collect.platform.video.VideoIngestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class CdpWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CdpWebSocketHandler.class);

    /** 前端长时间不回来就把槽位采集会话收掉，避免关掉浏览器后一直空转采集。 */
    private static final long IDLE_CLOSE_MS = 5 * 60 * 1000L;

    /** 槽位被另一个窗口接管时使用的关闭码，前端据此停止自动重连。 */
    private static final CloseStatus REPLACED = new CloseStatus(4001, "replaced by another window");

    private final CdpClient cdpClient;
    private final CdpTerminalStore terminals;
    private final VideoIngestService ingest;
    private final ObjectMapper mapper;
    /** 前端连接 → 采集会话，只用于转发前端消息。 */
    private final Map<String, CdpSession> sessions = new ConcurrentHashMap<>();
    /** 终端:槽位 → 常驻采集会话。前端切页/重连都复用它，采集状态不归零。 */
    private final Map<String, SlotRuntime> slots = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cdp-slot-reaper");
        t.setDaemon(true);
        return t;
    });

    public CdpWebSocketHandler(CdpClient cdpClient, CdpTerminalStore terminals,
                               VideoIngestService ingest, ObjectMapper mapper) {
        this.cdpClient = cdpClient;
        this.terminals = terminals;
        this.ingest = ingest;
        this.mapper = mapper;
        reaper.scheduleWithFixedDelay(this::reapIdleSlots, 60, 60, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String collector = String.valueOf(session.getAttributes().getOrDefault("code", ""));
        String terminalId = String.valueOf(session.getAttributes().getOrDefault("terminalId", ""));
        String slot = String.valueOf(session.getAttributes().getOrDefault("slot", ""));
        String slotKey = terminalId + ":" + slot;
        session.getAttributes().put("slotKey", slotKey);
        WebSocketSession previous = null;
        try {
            CdpClient client = cdpClient;
            if (!terminalId.isBlank()) {
                Map<String, Object> terminal = terminals.byCode(terminalId);
                if (terminal == null) {
                    throw new IllegalStateException("采集终端不存在：" + terminalId);
                }
                String role = String.valueOf(session.getAttributes().getOrDefault("role", ""));
                String owner = String.valueOf(terminal.get("owner_code"));
                if (!"admin".equalsIgnoreCase(role) && !owner.equals(collector)) {
                    throw new IllegalStateException("无权访问该采集终端：" + terminalId);
                }
                client = terminals.clientFor(terminal);
            }
            SlotRuntime rt = slots.computeIfAbsent(slotKey, SlotRuntime::new);
            CdpSession cdp;
            synchronized (rt) {
                cdp = rt.cdp;
                boolean reused = cdp != null && cdp.isAlive();
                if (!reused) {
                    if (cdp != null) {
                        log.info("槽位 {} 旧会话已失效，重建（listening={}）", slotKey, cdp.isListening());
                        cdp.close();
                    }
                    String pageWs = client.ensureDouyinPage(entryUrlFor(slot), slot);
                    CdpSession created = new CdpSession(pageWs, collector, terminalId, ingest, mapper,
                            payload -> { });
                    created.connect();
                    rt.cdp = created;
                    cdp = created;
                }
                log.info("槽位 {} 前端接入：{}（listening={} ingested={}）",
                        slotKey, reused ? "复用会话" : "新建会话",
                        cdp.isListening(), cdp.getIngestedTotal());
                // 出口永远指向该槽位当前的前端连接：切页重连只是换绑，采集不中断
                cdp.attach(payload -> {
                    WebSocketSession owner = rt.owner;
                    if (owner != null) {
                        send(owner, payload);
                    }
                });
                previous = rt.owner;
                rt.owner = session;
                rt.idleSince = 0;
            }
            sessions.put(session.getId(), cdp);
            if (previous != null && !previous.getId().equals(session.getId())) {
                // 同一终端同一槽位只保留最新那条前端连接；用 4001 告知旧页面不要再自动重连，
                // 否则两个窗口会互相顶掉，形成 3 秒一次的重连死循环。
                try {
                    previous.close(REPLACED);
                } catch (Exception ignore) {
                    // ignore
                }
            }
            Map<String, Object> ready = new java.util.LinkedHashMap<>();
            ready.put("type", "ready");
            ready.put("url", cdp.getCurrentUrl());
            ready.put("endpoint", client.baseUrl());
            ready.put("terminalId", terminalId);
            ready.put("slot", slot);
            ready.put("listening", cdp.isListening());
            ready.put("ingested", cdp.getIngestedTotal());
            ready.putAll(cdp.stats());
            send(session, mapper.writeValueAsString(ready));
        } catch (Exception ex) {
            log.warn("建立 CDP 会话失败: {}", ex.getMessage());
            try {
                send(session, mapper.writeValueAsString(Map.of(
                        "type", "error",
                        "message", "无法连接采集终端 " + (terminalId.isBlank() ? "" : terminalId + " ")
                                + "：" + ex.getMessage()
                                + "。请确认那台电脑已运行「抖音采集窗口」快捷方式（9222 调试端口）。")));
                session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        CdpSession cdp = sessions.get(session.getId());
        if (cdp == null) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(message.getPayload());
            cdp.onClientMessage(node);
        } catch (Exception ex) {
            log.debug("前端消息解析失败: {}", ex.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        String slotKey = String.valueOf(session.getAttributes().getOrDefault("slotKey", ""));
        SlotRuntime rt = slots.get(slotKey);
        if (rt == null) {
            return;
        }
        synchronized (rt) {
            // 只有当前占用者断开才摘出口；被顶掉的旧连接不影响新连接
            if (rt.owner != null && rt.owner.getId().equals(session.getId())) {
                rt.owner = null;
                rt.idleSince = System.currentTimeMillis();
                CdpSession cdp = rt.cdp;
                if (cdp != null) {
                    cdp.detach();
                    log.info("槽位 {} 前端断开，采集会话保留（listening={} ingested={}）",
                            slotKey, cdp.isListening(), cdp.getIngestedTotal());
                }
            }
        }
    }

    /** 前端离开后槽位采集继续跑；超过 IDLE_CLOSE_MS 没人回来才关掉。 */
    private void reapIdleSlots() {
        long now = System.currentTimeMillis();
        slots.forEach((key, rt) -> {
            synchronized (rt) {
                if (rt.owner != null || rt.idleSince == 0 || now - rt.idleSince < IDLE_CLOSE_MS) {
                    return;
                }
                CdpSession cdp = rt.cdp;
                rt.cdp = null;
                if (cdp != null) {
                    cdp.close();
                }
                slots.remove(key, rt);
                log.info("槽位 {} 无前端连接已 {} 分钟，关闭采集会话", key, IDLE_CLOSE_MS / 60000);
            }
        });
    }

    /** 槽位的起始页：精选走 /jingxuan 频道，搜索先进首页等用户输关键词。 */
    private static String entryUrlFor(String slot) {
        return switch (slot) {
            case "feed" -> "https://www.douyin.com/jingxuan";
            case "search" -> "https://www.douyin.com";
            default -> "https://www.douyin.com";
        };
    }

    private void send(WebSocketSession session, String payload) {
        if (!session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception ex) {
            log.debug("推送前端失败: {}", ex.getMessage());
        }
    }

    /** 一个槽位（终端 + feed/search）的常驻状态。 */
    private static final class SlotRuntime {
        private final String key;
        private volatile CdpSession cdp;
        private volatile WebSocketSession owner;
        private volatile long idleSince;

        private SlotRuntime(String key) {
            this.key = key;
        }

        @Override
        public String toString() {
            return key;
        }
    }
}
