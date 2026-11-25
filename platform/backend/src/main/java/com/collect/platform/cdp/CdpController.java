package com.collect.platform.cdp;

import com.collect.platform.common.ConflictException;
import com.collect.platform.common.ForbiddenException;
import com.collect.platform.common.Result;
import com.collect.platform.security.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/cdp")
public class CdpController {

    private static final List<String> SESSION_COOKIES = List.of(
            "sessionid", "sessionid_ss", "sid_tt", "sid_guard",
            "uid_tt", "uid_tt_ss", "passport_auth_status", "passport_auth_status_ss", "odin_tt");

    private static final String LOGIN_CLICK_JS = """
            (() => {
              const labels = ['登录', '立即登录', '扫码登录'];
              const nodes = Array.from(document.querySelectorAll('button, div, span, a'));
              for (const label of labels) {
                const el = nodes.find(n => n.offsetParent !== null
                  && (n.textContent || '').trim() === label
                  && n.getBoundingClientRect().width > 0);
                if (el) { el.click(); return label; }
              }
              return '';
            })()
            """;

    private final CdpClient cdpClient;
    private final CdpTerminalStore terminals;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CdpController(CdpClient cdpClient, CdpTerminalStore terminals, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.cdpClient = cdpClient;
        this.terminals = terminals;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status(@RequestParam(required = false) String terminalId) {
        Map<String, Object> terminal = resolve(terminalId);
        Map<String, Object> out;
        if (terminal == null) {
            out = new LinkedHashMap<>(cdpClient.status());
            out.put("terminal", null);
        } else {
            out = new LinkedHashMap<>(terminals.probe(terminal));
        }
        out.put("launcher", launcher());
        return Result.ok(out);
    }

    @PostMapping("/open")
    public Result<Map<String, Object>> open(@RequestBody(required = false) Map<String, Object> body) {
        String url = body == null ? "https://www.douyin.com"
                : String.valueOf(body.getOrDefault("url", "https://www.douyin.com"));
        Map<String, Object> terminal = resolve(body == null ? null : text(body.get("terminalId")));
        try {
            CdpClient client = terminal == null ? cdpClient : terminals.clientFor(terminal);
            String ws = client.openTab(url);
            return Result.ok(Map.of("ws", ws == null ? "" : ws, "url", url));
        } catch (Exception ex) {
            return Result.fail(ex.getMessage());
        }
    }

    /* ================= 采集终端 ================= */

    @GetMapping("/terminals")
    public Result<List<Map<String, Object>>> listTerminals() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : terminals.list()) {
            // 采集员只看得到登记在自己名下的终端；管理员看全部
            if (!canAccess(row)) {
                continue;
            }
            out.add(terminals.probe(row));
        }
        return Result.ok(out);
    }

    @PostMapping("/terminals")
    public Result<Map<String, Object>> createTerminal(@RequestBody Map<String, Object> body) {
        String code = text(body.get("code"));
        String name = text(body.get("name"));
        String host = text(body.get("host"));
        if (code == null) {
            throw new IllegalArgumentException("终端编码必填");
        }
        if (name == null) {
            throw new IllegalArgumentException("终端名称必填");
        }
        if (host == null) {
            throw new IllegalArgumentException("主机地址必填");
        }
        int port = body.get("port") == null ? 9222 : ((Number) body.get("port")).intValue();
        try {
            jdbc.update("""
                    INSERT INTO cdp_terminals (code, name, host, port, owner_code, douyin_account, note)
                    VALUES (?,?,?,?,?,?,?)
                    """, code, name, host, port, text(body.get("ownerCode")),
                    text(body.get("douyinAccount")), text(body.get("note")));
        } catch (DataIntegrityViolationException dup) {
            throw new ConflictException("terminal_exists", "终端编码已存在：" + code);
        }
        Long id = jdbc.queryForObject("SELECT id FROM cdp_terminals WHERE code = ?", Long.class, code);
        return Result.ok(Map.of("id", id, "code", code));
    }

    @PutMapping("/terminals/{id}")
    public Result<Map<String, Object>> updateTerminal(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> cur = terminals.byId(id);
        if (cur == null) {
            throw new IllegalArgumentException("终端不存在");
        }
        jdbc.update("""
                UPDATE cdp_terminals SET name = ?, host = ?, port = ?, owner_code = ?,
                       douyin_account = ?, note = ? WHERE id = ?
                """,
                pick(body.get("name"), cur.get("name")),
                pick(body.get("host"), cur.get("host")),
                body.get("port") == null ? ((Number) cur.get("port")).intValue() : ((Number) body.get("port")).intValue(),
                pick(body.get("ownerCode"), cur.get("owner_code")),
                pick(body.get("douyinAccount"), cur.get("douyin_account")),
                pick(body.get("note"), cur.get("note")),
                id);
        return Result.ok(Map.of("id", id, "code", String.valueOf(cur.get("code"))));
    }

    @DeleteMapping("/terminals/{id}")
    public Result<Map<String, Object>> deleteTerminal(@PathVariable long id) {
        int n = jdbc.update("DELETE FROM cdp_terminals WHERE id = ?", id);
        if (n == 0) {
            throw new IllegalArgumentException("终端不存在");
        }
        return Result.ok(Map.of("id", id, "deleted", true));
    }

    @GetMapping("/launcher")
    public Result<Map<String, Object>> launcherInfo() {
        return Result.ok(launcher());
    }

    /**
     * 采集端代理（cdp-proxy.js）定期上报自己当前的地址。
     * 局域网 IP 由 DHCP 分配、会变；让采集端主动上报，平台就不用每次人工改 IP。
     */
    @PostMapping("/announce")
    public Result<Map<String, Object>> announce(@RequestBody Map<String, Object> body) {
        String code = text(body.get("code"));
        String host = text(body.get("host"));
        if (code == null || host == null) {
            throw new IllegalArgumentException("终端编码和主机地址必填");
        }
        int port = body.get("port") == null ? 9223 : ((Number) body.get("port")).intValue();
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("端口不合法：" + port);
        }
        int n = jdbc.update("UPDATE cdp_terminals SET host = ?, port = ? WHERE code = ?", host, port, code);
        boolean created = false;
        if (n == 0) {
            // 开放式安装包：谁装上来自动登记一条，归属账号留空，等管理员在「终端管理」里分配
            String name = text(body.get("name"));
            if (name == null) {
                name = code;
            }
            try {
                jdbc.update("""
                        INSERT INTO cdp_terminals (code, name, host, port, owner_code, douyin_account, note)
                        VALUES (?,?,?,?,NULL,NULL,?)
                        """, code, name, host, port, "自动注册，待分配归属账号");
                created = true;
            } catch (DataIntegrityViolationException dup) {
                // 并发上报时另一条已经建好了，退回纯更新
                jdbc.update("UPDATE cdp_terminals SET host = ?, port = ? WHERE code = ?", host, port, code);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("code", code);
        out.put("host", host);
        out.put("port", port);
        out.put("created", created);
        return Result.ok(out);
    }

    /* ================= 抖音登录 ================= */

    @GetMapping("/douyin/status")
    public Result<Map<String, Object>> douyinStatus(@RequestParam(required = false) String terminalId) {
        Map<String, Object> terminal = resolve(terminalId);
        if (terminal == null) {
            return Result.ok(Map.of("available", false, "loggedIn", false, "message", "未选择采集终端"));
        }
        try {
            CdpProbe probe = openProbe(terminal);
            try {
                return Result.ok(inspectDouyin(probe, terminal));
            } finally {
                probe.close();
            }
        } catch (Exception ex) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", false);
            out.put("loggedIn", false);
            out.put("message", "读取登录状态失败：" + ex.getMessage());
            return Result.ok(out);
        }
    }

    @PostMapping("/douyin/login")
    public Result<Map<String, Object>> douyinLogin(@RequestBody(required = false) Map<String, Object> body) {
        String terminalId = body == null ? null : text(body.get("terminalId"));
        Map<String, Object> terminal = resolve(terminalId);
        if (terminal == null) {
            throw new IllegalArgumentException("先选择采集终端");
        }
        try {
            CdpClient client = terminals.clientFor(terminal);
            String ws = client.ensureDouyinPage("https://www.douyin.com/");
            try (CdpProbe probe = CdpProbe.open(ws, mapper)) {
                probe.call("Page.enable", null);
                try {
                    probe.call("Page.bringToFront", null);
                } catch (Exception ignore) {
                    // 部分环境不支持置前，不影响后续操作
                }
                probe.call("Page.navigate", Map.of("url", "https://www.douyin.com/"));
                Thread.sleep(2500);
                JsonNode hit = probe.call("Runtime.evaluate", Map.of(
                        "expression", LOGIN_CLICK_JS, "returnByValue", true));
                String clicked = hit.path("result").path("result").path("value").asText("");
                Map<String, Object> status = inspectDouyin(probe, terminal);
                status.put("clicked", clicked);
                status.put("message", clicked.isEmpty()
                        ? "已打开抖音首页；若没看到二维码，直接在画面里点右上角「登录」。"
                        : "已弹出扫码登录框，请用抖音 App 扫码。");
                return Result.ok(status);
            }
        } catch (Exception ex) {
            return Result.fail("打开抖音登录页失败：" + ex.getMessage());
        }
    }

    private CdpProbe openProbe(Map<String, Object> terminal) throws Exception {
        CdpClient client = terminals.clientFor(terminal);
        String ws = client.ensureDouyinPage("https://www.douyin.com");
        return CdpProbe.open(ws, mapper);
    }

    private Map<String, Object> inspectDouyin(CdpProbe probe, Map<String, Object> terminal) throws Exception {
        probe.call("Network.enable", null);
        JsonNode resp = probe.call("Network.getCookies",
                Map.of("urls", List.of("https://www.douyin.com", "https://www.douyin.com/")));
        List<JsonNode> cookies = new ArrayList<>();
        resp.path("result").path("cookies").forEach(cookies::add);

        String sessionId = null;
        long expiresAt = 0;
        for (JsonNode c : cookies) {
            String name = c.path("name").asText("");
            if ("sessionid".equals(name) || "sessionid_ss".equals(name)) {
                sessionId = c.path("value").asText("");
                long e = c.path("expires").asLong(-1);
                if (e > 0) {
                    expiresAt = Math.max(expiresAt, e * 1000);
                }
            }
        }
        boolean loggedIn = sessionId != null && !sessionId.isBlank();
        int persisted = loggedIn ? persistCookies(probe, cookies) : 0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("loggedIn", loggedIn);
        out.put("expiresAt", expiresAt > 0 ? expiresAt : null);
        out.put("persisted", persisted);
        out.put("account", terminal.get("douyin_account"));
        out.put("terminalId", terminal.get("code"));
        out.put("checkedAt", System.currentTimeMillis());
        return out;
    }

    /**
     * 抖音有时把 sessionid 写成「会话 cookie」，Chrome 一退出就丢。
     * 检测到这类关键 cookie 时按原值补 180 天过期时间写回专用配置目录，
     * 实现「扫码一次，后续开关机都不再登录」。
     */
    private int persistCookies(CdpProbe probe, List<JsonNode> cookies) {
        long expires = System.currentTimeMillis() / 1000 + 180L * 86400;
        int count = 0;
        for (JsonNode c : cookies) {
            String name = c.path("name").asText("");
            if (!SESSION_COOKIES.contains(name) || c.path("expires").asLong(-1) > 0) {
                continue;
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", name);
            p.put("value", c.path("value").asText(""));
            p.put("domain", c.path("domain").asText(".douyin.com"));
            p.put("path", c.path("path").asText("/"));
            p.put("secure", c.path("secure").asBoolean(true));
            p.put("httpOnly", c.path("httpOnly").asBoolean(true));
            p.put("expires", expires);
            String sameSite = c.path("sameSite").asText("");
            if (!sameSite.isBlank()) {
                p.put("sameSite", sameSite);
            }
            try {
                probe.call("Network.setCookie", p);
                count++;
            } catch (Exception ignore) {
                // 单个 cookie 写失败不影响整体状态
            }
        }
        return count;
    }

    /* ================= 内部 ================= */

    private Map<String, Object> resolve(String terminalId) {
        if (terminalId == null || terminalId.isBlank()) {
            return null;
        }
        Map<String, Object> t = terminals.byCode(terminalId);
        if (t == null) {
            throw new IllegalArgumentException("采集终端不存在：" + terminalId);
        }
        if (!canAccess(t)) {
            throw new ForbiddenException("terminal_forbidden", "无权访问该采集终端");
        }
        return t;
    }

    /** 管理员全量；其他角色只允许操作 owner_code 等于自己编码的终端。 */
    private static boolean canAccess(Map<String, Object> terminal) {
        UserContext me = UserContext.get();
        if (me == null) {
            return false;
        }
        if ("admin".equalsIgnoreCase(me.getRole())) {
            return true;
        }
        return Objects.equals(String.valueOf(terminal.get("owner_code")), me.getCode());
    }

    private static Object pick(Object value, Object fallback) {
        String s = text(value);
        return s == null ? fallback : s;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 平台后端跑在容器里拉不起 Windows 的 Chrome，所以把启动命令给前端展示。
     * 每台电脑第一次用，点一次桌面快捷方式即可。
     */
    private Map<String, Object> launcher() {
        String profile = "C:\\Users\\dataeye\\dya-chrome-profile";
        String chrome = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
        String altChrome = "C:\\Users\\%USERNAME%\\AppData\\Local\\Google\\Chrome\\Application\\chrome.exe";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profileDir", profile);
        out.put("chromePath", chrome);
        out.put("chromePathAlt", altChrome);
        out.put("command", "\"" + altChrome + "\" --remote-debugging-port=9222 "
                + "--user-data-dir=\"" + profile + "\" "
                + "--no-first-run --no-default-browser-check https://www.douyin.com");
        out.put("proxyHint", "Chrome 只监听 127.0.0.1，平台经 9223 代理访问；代理脚本在 C:\\Users\\dataeye\\dya-collect\\cdp-proxy.js");
        out.put("persistHint", "抖音登录态保存在专用配置目录 " + profile
                + "，扫码一次后重启电脑也不需要再登录；不要删除该目录，也不要用无痕模式。");
        return out;
    }
}
