package com.collect.platform.security;

public final class UserContext {
    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    private final Long userId;
    private final String code;
    private final String role;
    private final String ip;

    public UserContext(Long userId, String code, String role, String ip) {
        this.userId = userId;
        this.code = code;
        this.role = role;
        this.ip = ip;
    }

    public static void set(UserContext ctx) {
        HOLDER.set(ctx);
    }

    public static UserContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public Long getUserId() {
        return userId;
    }

    public String getCode() {
        return code;
    }

    public String getRole() {
        return role;
    }

    public String getIp() {
        return ip;
    }
}
