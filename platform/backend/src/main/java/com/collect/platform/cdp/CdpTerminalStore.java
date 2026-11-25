package com.collect.platform.cdp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集终端注册表：一台终端 = 一台电脑上的专用 Chrome（host + 调试端口）。
 * 支持多终端，采集员各绑各的。
 */
@Component
public class CdpTerminalStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CdpTerminalStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT id, code, name, host, port, owner_code, douyin_account, note, created_at
                FROM cdp_terminals ORDER BY id
                """);
    }

    public Map<String, Object> byCode(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, code, name, host, port, owner_code, douyin_account, note
                FROM cdp_terminals WHERE code = ?
                """, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> byId(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, code, name, host, port, owner_code, douyin_account, note
                FROM cdp_terminals WHERE id = ?
                """, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按终端建一个只读 CDP 客户端（不复用全局默认端点）。 */
    public CdpClient clientFor(Map<String, Object> terminal) {
        String host = String.valueOf(terminal.get("host"));
        int port = ((Number) terminal.get("port")).intValue();
        return new CdpClient(host, port, mapper);
    }

    /** 终端在线状态：探一次 /json/version，顺便带出标签页标题。 */
    public Map<String, Object> probe(Map<String, Object> terminal) {
        Map<String, Object> out = new LinkedHashMap<>(terminal);
        CdpClient client = clientFor(terminal);
        Map<String, Object> st = client.status();
        out.put("online", Boolean.TRUE.equals(st.get("available")));
        out.put("endpoint", client.baseUrl());
        out.put("browser", st.get("browser"));
        out.put("error", st.get("error"));
        out.put("pages", st.get("pages"));
        return out;
    }
}
