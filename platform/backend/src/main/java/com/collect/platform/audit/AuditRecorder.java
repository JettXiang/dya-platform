package com.collect.platform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 审计记录器（G03）：
 * - DB 可用时不因队列满而丢（满则同步直写）
 * - DB 不可用时写本地 append-only fallback（每次 append 后 fsync）
 * - 关停/退出前 drain，并记录结果
 */
@Component
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private static final String INSERT_SQL = """
            INSERT IGNORE INTO audit_logs
            (event_id, actor_id, operator, operator_type, triggered_by, action, method, uri, params,
             object_type, object_id, detail_json, ip, success, error_message, duration_ms, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Path fallbackDir;
    private final int drainTimeoutMs;

    private final BlockingQueue<AuditEvent> queue;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final ExecutorService consumer;

    public AuditRecorder(JdbcTemplate jdbcTemplate,
                         ObjectMapper objectMapper,
                         @Value("${app.audit.fallback-dir}") String fallbackDir,
                         @Value("${app.audit.queue-capacity:10000}") int capacity,
                         @Value("${app.audit.drain-timeout-ms:5000}") int drainTimeoutMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.fallbackDir = Path.of(fallbackDir);
        this.drainTimeoutMs = drainTimeoutMs;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.consumer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "audit-consumer");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    void start() throws IOException {
        Files.createDirectories(fallbackDir);
        consumer.submit(this::consumeLoop);
        log.info("审计队列已启动，fallback 目录：{}", fallbackDir.toAbsolutePath());
    }

    public void record(AuditEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(java.util.UUID.randomUUID().toString());
        }
        if (!accepting.get() || !queue.offer(event)) {
            writeWithFallback(List.of(event));
        }
    }

    private void consumeLoop() {
        List<AuditEvent> batch = new ArrayList<>(50);
        while (accepting.get() || !queue.isEmpty()) {
            try {
                AuditEvent first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, 49);
                writeWithFallback(batch);
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("AUDIT_WRITE_FAILED {}", e.getMessage(), e);
                batch.forEach(this::appendFallback);
                batch.clear();
            }
        }
    }

    private void writeWithFallback(List<AuditEvent> events) {
        try {
            jdbcTemplate.batchUpdate(INSERT_SQL, events, events.size(), (ps, e) -> {
                ps.setString(1, e.getEventId());
                if (e.getActorId() == null) ps.setNull(2, java.sql.Types.BIGINT); else ps.setLong(2, e.getActorId());
                ps.setString(3, e.getOperator());
                ps.setString(4, e.getOperatorType());
                if (e.getTriggeredBy() == null) ps.setNull(5, java.sql.Types.BIGINT); else ps.setLong(5, e.getTriggeredBy());
                ps.setString(6, e.getAction());
                ps.setString(7, e.getMethod());
                ps.setString(8, e.getUri());
                ps.setString(9, e.getParams());
                ps.setString(10, e.getObjectType());
                ps.setString(11, e.getObjectId());
                ps.setString(12, e.getDetailJson());
                ps.setString(13, e.getIp());
                ps.setBoolean(14, e.isSuccess());
                ps.setString(15, e.getErrorMessage());
                if (e.getDurationMs() == null) ps.setNull(16, java.sql.Types.INTEGER);
                else ps.setInt(16, e.getDurationMs());
                ps.setTimestamp(17, java.sql.Timestamp.valueOf(
                        e.getCreatedAt() == null ? java.time.LocalDateTime.now() : e.getCreatedAt()));
            });
        } catch (Exception ex) {
            log.warn("审计入库失败，转 fallback：{}", ex.getMessage());
            events.forEach(this::appendFallback);
        }
    }

    /** 先脱敏、再截断（S02）；每条 append 后 fsync（P1） */
    private void appendFallback(AuditEvent event) {
        try {
            Files.createDirectories(fallbackDir);
            Path file = fallbackDir.resolve("audit-" + LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ".jsonl");
            String line = objectMapper.writeValueAsString(event) + System.lineSeparator();
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                channel.write(StandardCharsets.UTF_8.encode(line));
                channel.force(true);
            }
        } catch (IOException io) {
            log.error("AUDIT_FALLBACK_FAILED 审计可能丢失：{}", io.getMessage(), io);
            throw new AuditLostException("审计可能丢失", io);
        }
    }

    /** API 正常关停时调用：停止接收、drain、记录结果 */
    @PreDestroy
    public void drain() {
        accepting.set(false);
        consumer.shutdown();
        try {
            boolean done = consumer.awaitTermination(drainTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("AUDIT_DRAIN done={} remaining={}", done, queue.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("AUDIT_DRAIN interrupted, remaining={}", queue.size());
        }
    }

    public int queueSize() {
        return queue.size();
    }

    public Path getFallbackDir() {
        return fallbackDir;
    }

    public static class AuditLostException extends RuntimeException {
        public AuditLostException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
