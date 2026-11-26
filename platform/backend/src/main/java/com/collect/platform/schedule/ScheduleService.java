package com.collect.platform.schedule;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class ScheduleService {

    private final ScheduleRepository repo;

    public ScheduleService(ScheduleRepository repo) {
        this.repo = repo;
    }

    public List<Map<String, Object>> list(String date) {
        return repo.listByDate(LocalDate.parse(date));
    }

    public void upsert(Map<String, Object> body) {
        String date = String.valueOf(body.get("date"));
        String accountCode = String.valueOf(body.get("accountCode"));
        String status = String.valueOf(body.get("status"));
        Long accountId = repo.findAccountId(accountCode);
        repo.upsert(accountId, LocalDate.parse(date), status, body.get("note"));
    }
}
