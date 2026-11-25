package com.collect.platform.cdp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 采集终端存储层的单元测试：行映射、按终端建客户端、探测失败时的兜底。
 */
@ExtendWith(MockitoExtension.class)
class CdpTerminalStoreTest {

    @Mock
    private JdbcTemplate jdbc;

    private CdpTerminalStore store;

    @BeforeEach
    void setUp() {
        store = new CdpTerminalStore(jdbc, new ObjectMapper());
    }

    @Test
    @DisplayName("列表按终端逐行返回")
    void listReturnsRows() {
        List<Map<String, Object>> rows = List.of(row("PC-01", "INTERN01"), row("PC-02", "INTERN02"));
        when(jdbc.queryForList(anyString())).thenReturn(rows);

        assertThat(store.list()).hasSize(2)
                .extracting(m -> m.get("code"))
                .containsExactly("PC-01", "PC-02");
    }

    @Test
    @DisplayName("按编码查得到返回该行，查不到返回 null")
    void byCodeHandlesHitAndMiss() {
        Map<String, Object> hit = row("PC-01", "INTERN01");
        when(jdbc.queryForList(anyString(), eq("PC-01"))).thenReturn(List.of(hit));
        when(jdbc.queryForList(anyString(), eq("PC-404"))).thenReturn(List.of());

        assertThat(store.byCode("PC-01")).isSameAs(hit);
        assertThat(store.byCode("PC-404")).isNull();
    }

    @Test
    @DisplayName("按 id 查得到返回该行，查不到返回 null")
    void byIdHandlesHitAndMiss() {
        Map<String, Object> hit = row("PC-01", "INTERN01");
        when(jdbc.queryForList(anyString(), eq(3L))).thenReturn(List.of(hit));
        when(jdbc.queryForList(anyString(), eq(99L))).thenReturn(List.of());

        assertThat(store.byId(3L)).isSameAs(hit);
        assertThat(store.byId(99L)).isNull();
    }

    @Test
    @DisplayName("按终端登记的 host:port 建 CDP 客户端，不会串到默认端点")
    void clientForUsesTerminalEndpoint() {
        Map<String, Object> terminal = row("PC-01", "INTERN01");
        terminal.put("host", "10.1.2.113");
        terminal.put("port", 9223);

        CdpClient client = store.clientFor(terminal);

        assertThat(client.baseUrl()).isEqualTo("http://10.1.2.113:9223");
    }

    @Test
    @DisplayName("终端连不上时探测返回 online=false 并带错误原因，不抛异常")
    void probeReportsOfflineWhenUnreachable() {
        Map<String, Object> terminal = row("PC-01", "INTERN01");
        // 1 端口不会有 CDP 服务，连接立即被拒，用来验证兜底分支
        terminal.put("host", "127.0.0.1");
        terminal.put("port", 1);

        Map<String, Object> out = store.probe(terminal);

        assertThat(out).containsEntry("online", false)
                .containsEntry("endpoint", "http://127.0.0.1:1")
                .containsEntry("code", "PC-01");
        assertThat(out.get("error")).isNotNull();
        assertThat(terminal).doesNotContainKey("online");
    }

    private static Map<String, Object> row(String code, String owner) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 3L);
        row.put("code", code);
        row.put("name", "终端" + code);
        row.put("host", "10.0.0.1");
        row.put("port", 9222);
        row.put("owner_code", owner);
        row.put("douyin_account", "douyin-01");
        row.put("note", "备注");
        return row;
    }
}
