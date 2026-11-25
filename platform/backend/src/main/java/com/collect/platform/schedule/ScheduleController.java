package com.collect.platform.schedule;

import com.collect.platform.audit.Audited;
import com.collect.platform.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final JdbcTemplate jdbc;

    public ScheduleController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam String date) {
        return Result.ok(jdbc.queryForList("""
                SELECT a.code AS account_code, u.name, s.status, s.note
                FROM accounts a
                JOIN users u ON u.id = a.user_id
                LEFT JOIN schedules s ON s.account_id = a.id AND s.`date` = ?
                WHERE u.status = 1
                ORDER BY a.code
                """, LocalDate.parse(date)));
    }

    @PutMapping
    @Audited("改排班")
    public Result<Void> upsert(@RequestBody Map<String, Object> body) {
        String date = String.valueOf(body.get("date"));
        String accountCode = String.valueOf(body.get("accountCode"));
        String status = String.valueOf(body.get("status"));
        Long accountId = jdbc.queryForObject(
                "SELECT id FROM accounts WHERE code = ?", Long.class, accountCode);
        jdbc.update("""
                INSERT INTO schedules (account_id, `date`, status, note) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE status = VALUES(status), note = VALUES(note)
                """, accountId, LocalDate.parse(date), status, body.get("note"));
        return Result.ok(null);
    }
}
