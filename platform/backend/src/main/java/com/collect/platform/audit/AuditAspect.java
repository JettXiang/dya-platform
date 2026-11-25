package com.collect.platform.audit;

import com.collect.platform.common.Result;
import com.collect.platform.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;

@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
    private static final int PARAM_LIMIT = 500;
    private static final int ERROR_LIMIT = 2000;

    private final AuditRecorder recorder;

    public AuditAspect(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = null;
        boolean success = true;
        String error = null;
        try {
            result = pjp.proceed();
            if (result instanceof Result<?> r) {
                success = r.isSuccess();
                if (!success) {
                    error = r.getMessage();
                }
            }
            return result;
        } catch (Throwable t) {
            success = false;
            error = t.getMessage();
            throw t;
        } finally {
            long duration = System.currentTimeMillis() - start;
            log.info("【审计】{} | {} | {} | {}ms | {}", audited.value(),
                    currentOperator(), success ? "SUCCESS" : "FAIL", duration, error);
            if (!audited.skip()) {
                try {
                    recorder.record(buildEvent(pjp, audited, success, error, duration));
                } catch (Exception e) {
                    log.error("AUDIT_RECORD_FAILED {}", e.getMessage(), e);
                }
            }
        }
    }

    private AuditEvent buildEvent(ProceedingJoinPoint pjp, Audited audited,
                                  boolean success, String error, long duration) {
        AuditEvent e = new AuditEvent();
        var ctx = UserContext.get();
        if (ctx != null) {
            e.setActorId(ctx.getUserId());
            e.setOperator(ctx.getCode());
            e.setOperatorType(ctx.getRole());
            e.setIp(ctx.getIp());
        } else {
            e.setOperator("system");
            e.setOperatorType("system");
        }
        e.setAction(audited.value());
        e.setMethod(pjp.getSignature().getDeclaringType().getSimpleName() + "." + pjp.getSignature().getName());
        e.setObjectType(audited.objectType());

        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            e.setUri(req.getRequestURI());
        }

        String params = Arrays.stream(pjp.getArgs())
                .filter(a -> a != null && !(a instanceof HttpServletRequest) && !(a instanceof HttpServletResponse))
                .map(a -> {
                    try {
                        return String.valueOf(a);
                    } catch (Exception ignore) {
                        return a.getClass().getSimpleName();
                    }
                })
                .collect(Collectors.joining(", "));
        params = Masker.mask(params);
        e.setParams(params.length() > PARAM_LIMIT ? params.substring(0, PARAM_LIMIT) : params);

        if (audited.captureObjectId() && pjp.getArgs().length > 0 && pjp.getArgs()[0] != null) {
            e.setObjectId(String.valueOf(pjp.getArgs()[0]));
        }
        e.setSuccess(success);
        e.setErrorMessage(error == null ? null : Masker.mask(truncate(error, ERROR_LIMIT)));
        e.setDurationMs((int) Math.min(Integer.MAX_VALUE, duration));
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }

    private String currentOperator() {
        var ctx = UserContext.get();
        return ctx == null ? "system" : ctx.getCode();
    }

    private String truncate(String s, int limit) {
        return s.length() > limit ? s.substring(0, limit) : s;
    }
}
