package com.ticketai.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * JWT 双 token 签发与校验（DEV_DOC §5.6）。
 * access token 30 分钟 / refresh token 7 天。
 * P0-7/M-1：token 携带 jti 与 type claim（access/refresh 不可互用），用于吊销与类型区分。
 */
@Component
public class JwtTokenProvider {

    /** token 类型 claim */
    public static final String CLAIM_TYPE = "type";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    /** 吊销黑名单 key 前缀（登出时将 jti 置入，TTL=token 剩余有效期） */
    public static final String BLACKLIST_KEY_PREFIX = "jwt:blacklist:";

    private final SecretKey secretKey;
    private final long accessExpireMs;
    private final long refreshExpireMs;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-expire-minutes}") long accessExpireMinutes,
            @Value("${app.jwt.refresh-expire-days}") long refreshExpireDays) {
        if (secret == null || secret.trim().length() < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret 未配置或长度不足 32 字符：生产环境必须通过 JWT_SECRET 环境变量注入随机密钥，"
                            + "禁止使用内置默认值（会导致令牌可被任意伪造）");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpireMs = accessExpireMinutes * 60_000L;
        this.refreshExpireMs = refreshExpireDays * 24 * 60 * 60_000L;
    }

    public String createAccessToken(Long userId, String username, Long agentId, List<String> permissions) {
        return build(userId, username, agentId, permissions, TYPE_ACCESS, accessExpireMs);
    }

    public String createRefreshToken(Long userId, String username) {
        return build(userId, username, null, null, TYPE_REFRESH, refreshExpireMs);
    }

    private String build(Long userId, String username, Long agentId, List<String> permissions,
                         String type, long expireMs) {
        Date now = new Date();
        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString()) // jti：吊销黑名单依据
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, type)
                .claim("username", username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMs))
                .signWith(secretKey);
        if (agentId != null) {
            builder.claim("agentId", agentId);
        }
        if (permissions != null) {
            builder.claim("permissions", permissions);
        }
        return builder.compact();
    }

    /**
     * 解析并校验 token，返回 claims；无效/过期抛 io.jsonwebtoken.JwtException。
     */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(secretKey).build()
                .parseSignedClaims(token).getPayload();
    }

    /**
     * 转换为访问用户上下文。仅接受 access 类型 token；refresh token 冒充访问令牌直接被拒。
     */
    public LoginUser toLoginUser(String token) {
        return toLoginUser(parse(token));
    }

    @SuppressWarnings("unchecked")
    public LoginUser toLoginUser(Claims claims) {
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("令牌类型错误：refresh token 不能作为访问令牌使用");
        }
        Long userId = Long.valueOf(claims.getSubject());
        String username = claims.get("username", String.class);
        Long agentId = claims.get("agentId", Long.class);
        List<String> permissions = claims.get("permissions", List.class);
        return new LoginUser(userId, username, agentId, permissions);
    }

    public long getRefreshExpireMs() {
        return refreshExpireMs;
    }
}
