package com.collect.platform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * fallback 重放（G03 / P2）：
 * - 固定执行者：只有拿到 replay.lock 的进程执行
 * - 按 event_id 幂等（INSERT IGNORE）
 * - 坏行隔离到 .bad，不拖停整份重放
 * - 成功文件改名为 .done，保留 7 天后清理
 */
@Component
public class AuditReplayRunner {

    private static final Logger log = LoggerFactory.getLogger(AuditReplayRunner.class);

    private final AuditRepository repo;
    private final ObjectMapper objectMapper;
    private final Path dir;
    private final boolean enabled;

    public AuditReplayRunner(AuditRepository repo,
                             ObjectMapper objectMapper,
                             @Value("${app.audit.fallback-dir}") String dir,
                             @Value("${app.audit.replay-enabled:true}") boolean enabled) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.dir = Path.of(dir);
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        replay();
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void replay() {
        if (!enabled) {
            return;
        }
        Path lockFile = dir.resolve("replay.lock");
        try {
            Files.createDirectories(dir);
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    return;
                }
                processFiles();
            }
        } catch (IOException e) {
            log.warn("AUDIT_REPLAY_SKIP {}", e.getMessage());
        }
    }

    private void processFiles() {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .filter(p -> !p.getFileName().toString().contains(today))
                    .forEach(this::processFile);
        } catch (IOException e) {
            log.warn("AUDIT_REPLAY_LIST_FAILED {}", e.getMessage());
        }
        cleanupDone();
    }

    private void processFile(Path file) {
        int ok = 0;
        int bad = 0;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Path badFile = file.resolveSibling(file.getFileName() + ".bad");
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    AuditEvent event = objectMapper.readValue(line, AuditEvent.class);
                    insert(event);
                    ok++;
                } catch (Exception ex) {
                    bad++;
                    Files.writeString(badFile, line + System.lineSeparator(),
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    log.warn("AUDIT_REPLAY_BAD_LINE file={} reason={}", file.getFileName(), ex.getMessage());
                }
            }
            Path done = file.resolveSibling(file.getFileName() + ".done");
            Files.move(file, done, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("AUDIT_REPLAY_DONE file={} ok={} bad={}", file.getFileName(), ok, bad);
        } catch (IOException e) {
            log.error("AUDIT_REPLAY_FAILED file={} {}", file.getFileName(), e.getMessage());
        }
    }

    private void insert(AuditEvent e) {
        repo.insert(e);
    }

    private void cleanupDone() {
        long cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".done"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() < cutoff;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
