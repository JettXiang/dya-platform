package com.collect.platform.extension.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

public class IngestDtos {

    @Data
    public static class ChunkRequest {
        @NotBlank
        private String accountCode;
        @NotBlank
        private String clientRunId;
        /** A01：业务日必填，按 Asia/Shanghai 计算 */
        @NotBlank
        private String businessDate;
        @NotNull
        private Integer chunkSeq;
        private boolean finalChunk;
        private Integer finalSeq;
        private int totalCount;
        @NotBlank
        private String payloadHash;
        private String keyword;
        private String startedAtClient;
        private String endedAtClient;
        private int scrolls;
        private int scanned;
        private int matched;
        private List<Item> items;
    }

    @Data
    public static class Item {
        @NotBlank
        private String secUid;
        private boolean hasMix;
        private boolean hasAi;
    }

    @Data
    public static class DayFinalRequest {
        @NotBlank
        private String accountCode;
        @NotNull
        private String date;
        private int expectedRuns;
        private List<String> expectedRunIds;
        private int clientPendingChunks;
        @NotBlank
        private String contentHash;
        private int revision;
    }

    @Data
    public static class HeartbeatRequest {
        @NotBlank
        private String accountCode;
        private String state;
        private String runId;
        private String keyword;
        private int scanned;
        private int matched;
    }

    @Data
    public static class RunVoidRequest {
        @NotBlank
        private String accountCode;
        @NotBlank
        private String clientRunId;
        @NotBlank
        private String businessDate;
        @NotBlank
        private String reason;
        /** 幂等键；同一键不同参数视为冲突 */
        @NotBlank
        private String clientRequestId;
    }
}
