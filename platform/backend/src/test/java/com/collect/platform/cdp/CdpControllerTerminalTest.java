package com.collect.platform.cdp;

import com.collect.platform.common.ConflictException;
import com.collect.platform.common.ForbiddenException;
import com.collect.platform.common.Result;
import com.collect.platform.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 终端管理接口的单元测试：列表可见性、增改删校验、归属校验。
 * 只 mock 数据库与终端存储，不启 Spring 容器、不连数据库。
 */
@ExtendWith(MockitoExtension.class)
class CdpControllerTerminalTest {

    @Mock
    private CdpClient cdpClient;

    @Mock
    private CdpTerminalStore terminals;

    @Mock
    private JdbcTemplate jdbc;

    private CdpController controller;

    @BeforeEach
    void setUp() {
        controller = new CdpController(cdpClient, terminals, jdbc, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /* ================= 列表可见性 ================= */

    @Test
    @DisplayName("管理员能看到全部终端，并逐个探测在线状态")
    void adminSeesAllTerminals() {
        login("INTERN01", "admin");
        List<Map<String, Object>> rows = List.of(
                terminalRow("PC-01", "INTERN01"),
                terminalRow("PC-02", "INTERN02"));
        when(terminals.list()).thenReturn(rows);
        stubProbe();

        Result<List<Map<String, Object>>> res = controller.listTerminals();

        assertThat(res.isSuccess()).isTrue();
        assertThat(res.getData()).extracting(m -> m.get("code")).containsExactly("PC-01", "PC-02");
        assertThat(res.getData()).allSatisfy(m -> assertThat(m.get("online")).isEqualTo(true));
        verify(terminals).probe(rows.get(0));
        verify(terminals).probe(rows.get(1));
    }

    @Test
    @DisplayName("采集员只看到 owner_code 等于自己的终端，别人的终端连探测都不做")
    void collectorSeesOnlyOwnTerminal() {
        login("INTERN02", "collector");
        Map<String, Object> mine = terminalRow("PC-02", "INTERN02");
        Map<String, Object> other = terminalRow("PC-01", "INTERN01");
        when(terminals.list()).thenReturn(List.of(other, mine));
        stubProbe();

        Result<List<Map<String, Object>>> res = controller.listTerminals();

        assertThat(res.getData()).extracting(m -> m.get("code")).containsExactly("PC-02");
        verify(terminals).probe(mine);
        verify(terminals, never()).probe(other);
    }

    @Test
    @DisplayName("没有登录上下文时列表为空，避免漏配权限导致越权")
    void anonymousSeesNothing() {
        when(terminals.list()).thenReturn(List.of(terminalRow("PC-01", "INTERN01")));

        Result<List<Map<String, Object>>> res = controller.listTerminals();

        assertThat(res.getData()).isEmpty();
        verify(terminals, never()).probe(any());
    }

    /* ================= 新增 ================= */

    @Test
    @DisplayName("新增终端：编码、名称、主机地址必填")
    void createRequiresCodeNameHost() {
        assertThatThrownBy(() -> controller.createTerminal(Map.of("name", "终端", "host", "10.0.0.1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("终端编码必填");
        assertThatThrownBy(() -> controller.createTerminal(Map.of("code", "PC-01", "host", "10.0.0.1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("终端名称必填");
        assertThatThrownBy(() -> controller.createTerminal(Map.of("code", "PC-01", "name", "终端")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("主机地址必填");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("新增终端：编码重复转成 409 冲突而不是 500")
    void createDuplicateCodeThrowsConflict() {
        Map<String, Object> body = Map.of("code", "PC-01", "name", "终端", "host", "10.0.0.1");
        doThrow(new DataIntegrityViolationException("duplicate"))
                .when(jdbc).update(anyString(), any(Object[].class));

        assertThatThrownBy(() -> controller.createTerminal(body))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PC-01");
    }

    @Test
    @DisplayName("新增终端：编码去空格、端口缺省 9222，返回新 id")
    void createTrimsCodeAndDefaultsPort() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "  PC-01  ");
        body.put("name", "终端");
        body.put("host", "10.0.0.1");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(7L);

        Result<Map<String, Object>> res = controller.createTerminal(body);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertThat(args.getValue()[0]).isEqualTo("PC-01");
        assertThat(args.getValue()[3]).isEqualTo(9222);
        assertThat(res.getData()).containsEntry("id", 7L).containsEntry("code", "PC-01");
    }

    /* ================= 修改 ================= */

    @Test
    @DisplayName("修改不存在的终端直接报错，不落库")
    void updateUnknownTerminalThrows() {
        when(terminals.byId(9L)).thenReturn(null);

        assertThatThrownBy(() -> controller.updateTerminal(9L, Map.of("name", "新名")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("终端不存在");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("修改只传名称时，其余字段沿用原值")
    void updateKeepsOmittedFields() {
        Map<String, Object> cur = terminalRow("PC-01", "INTERN01");
        when(terminals.byId(3L)).thenReturn(cur);

        Result<Map<String, Object>> res = controller.updateTerminal(3L, Map.of("name", "新名"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        Object[] v = args.getValue();
        assertThat(v[0]).isEqualTo("新名");
        assertThat(v[1]).isEqualTo("10.0.0.1");
        assertThat(v[2]).isEqualTo(9222);
        assertThat(v[3]).isEqualTo("INTERN01");
        assertThat(v[4]).isEqualTo("douyin-01");
        assertThat(v[5]).isEqualTo("备注");
        assertThat(v[6]).isEqualTo(3L);
        assertThat(res.getData()).containsEntry("code", "PC-01");
    }

    /* ================= 删除 ================= */

    @Test
    @DisplayName("删除不存在的终端报错，不返回成功")
    void deleteUnknownTerminalThrows() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> controller.deleteTerminal(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("终端不存在");
    }

    @Test
    @DisplayName("删除成功返回 deleted=true")
    void deleteTerminalOk() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        Result<Map<String, Object>> res = controller.deleteTerminal(5L);

        assertThat(res.isSuccess()).isTrue();
        assertThat(res.getData()).containsEntry("deleted", true).containsEntry("id", 5L);
    }

    /* ================= 归属校验 ================= */

    @Test
    @DisplayName("未选终端时抖音状态接口返回未登录提示，不抛异常")
    void douyinStatusWithoutTerminal() {
        Result<Map<String, Object>> res = controller.douyinStatus(null);

        assertThat(res.getData())
                .containsEntry("available", false)
                .containsEntry("loggedIn", false)
                .containsEntry("message", "未选择采集终端");
    }

    @Test
    @DisplayName("终端编码不存在时报错")
    void douyinStatusUnknownTerminalThrows() {
        when(terminals.byCode("PC-404")).thenReturn(null);

        assertThatThrownBy(() -> controller.douyinStatus("PC-404"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PC-404");
    }

    @Test
    @DisplayName("采集员访问别人终端被拒（403），不会走到探测")
    void collectorCannotAccessOtherTerminal() {
        login("INTERN02", "collector");
        when(terminals.byCode("PC-01")).thenReturn(terminalRow("PC-01", "INTERN01"));

        assertThatThrownBy(() -> controller.douyinStatus("PC-01"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("无权访问该采集终端");
        verify(terminals, never()).probe(any());
        verify(terminals, never()).clientFor(any());
    }

    @Test
    @DisplayName("管理员可以查看别人名下的终端状态")
    void adminCanAccessOtherTerminal() {
        login("INTERN01", "admin");
        Map<String, Object> other = terminalRow("PC-02", "INTERN02");
        when(terminals.byCode("PC-02")).thenReturn(other);
        stubProbe();

        Result<Map<String, Object>> res = controller.status("PC-02");

        assertThat(res.isSuccess()).isTrue();
        assertThat(res.getData()).containsEntry("code", "PC-02").containsEntry("online", true);
        verify(terminals).probe(other);
    }

    /* ================= 辅助 ================= */

    private static void login(String code, String role) {
        UserContext.set(new UserContext(1L, code, role, "127.0.0.1"));
    }

    private static Map<String, Object> terminalRow(String code, String owner) {
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

    private void stubProbe() {
        when(terminals.probe(any())).thenAnswer(inv -> {
            Map<String, Object> t = inv.getArgument(0);
            Map<String, Object> out = new LinkedHashMap<>(t);
            out.put("online", true);
            return out;
        });
    }
}
