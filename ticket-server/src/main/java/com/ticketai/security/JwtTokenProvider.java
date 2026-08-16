package com.ticketai.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * JWT 双 token 签发与校验（DEV_DOC §5.6）。
 * access token 30 分钟 / refresh token 7 天。
 */
@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long accessExpireMs;
    private final long refreshExpireMs;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-expire-minutes}") long accessExpireMinutes,
            @Value("${app.jwt.refresh-expire-days}") long refreshExpireDays) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpireMs = accessExpireMinutes * 60_000L;
        this.refreshExpireMs = refreshExpireDays * 24 * 60 * 60_000L;
    }

    public String createAccessToken(Long userId, String username, Long agentId, List<String> permissions) {
        return build(userId, username, agentId, permissions, accessExpireMs);
    }

    public String createRefreshToken(Long userId, String username) {
        return build(userId, username, null, null, refreshExpireMs);
    }

    private String build(Long userId, String username, Long agentId, List<String> permissions, long expireMs) {
        Date now = new Date();
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
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

    @SuppressWarnings("unchecked")
    public LoginUser toLoginUser(String token) {
        Claims claims = parse(token);
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
