package com.collect.platform.audit;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AuditEvent {
    private String eventId;
    private Long actorId;
    private String operator;
    private String operatorType;
    private Long triggeredBy;
    private String action;
    private String method;
    private String uri;
    private String params;
    private String objectType;
    private String objectId;
    private String detailJson;
    private String ip;
    private boolean success = true;
    private String errorMessage;
    private Integer durationMs;
    private LocalDateTime createdAt;
}
