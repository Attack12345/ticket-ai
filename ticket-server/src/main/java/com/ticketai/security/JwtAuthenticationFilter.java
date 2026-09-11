package com.ticketai.security;

import com.ticketai.security.JwtTokenProvider;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器（DEV_DOC §5.6.3）：解析 Bearer token → LoginUser → 写入
 * SecurityContext（供 @PreAuthorize）与 UserContextHolder。
 * P0-7/M-1：登出后 jti 进黑名单，黑名单内的 token 直接拒绝；type=refresh 冒充 access 由
 * JwtTokenProvider.toLoginUser(Claims) 拒绝。Redis 不可用时黑名单检查失败放开（签名本身有效）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                var claims = tokenProvider.parse(token);
                String jti = claims.getId();
                if (jti != null && Boolean.TRUE.equals(
                        stringRedisTemplate.hasKey(JwtTokenProvider.BLACKLIST_KEY_PREFIX + jti))) {
                    log.info("令牌已吊销(jti={})，拒绝访问", jti);
                } else {
                    LoginUser loginUser = tokenProvider.toLoginUser(claims); // 内部校验 type=access
                    List<SimpleGrantedAuthority> authorities = loginUser.getPermissions() == null ? List.of()
                            : loginUser.getPermissions().stream().map(SimpleGrantedAuthority::new).toList();
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    UserContextHolder.set(loginUser);
                }
            } catch (JwtException | IllegalArgumentException e) {
                // token 无效：不设置认证，后续由 Security 返回 401
                log.debug("JWT 解析失败: {}", e.getMessage());
            } catch (Exception e) {
                // 黑名单检查失败（如 Redis 不可用）：签名 token 本身有效 → 放行（可用性优先，fail-open）
                log.warn("令牌黑名单检查异常，放行请求: {}", e.toString());
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
        }
    }
}