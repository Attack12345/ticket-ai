package com.ticketai.security;

/**
 * 当前登录用户 ThreadLocal 上下文。
 * 由 JwtAuthenticationFilter 写入；使用后必须清理（filter 的 finally）。
 */
public final class UserContextHolder {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static Long getUserId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.getUserId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
