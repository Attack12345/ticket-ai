package com.ticketai.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                LoginUser loginUser = tokenProvider.toLoginUser(token);
                List<SimpleGrantedAuthority> authorities = loginUser.getPermissions() == null ? List.of()
                        : loginUser.getPermissions().stream().map(SimpleGrantedAuthority::new).toList();
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                UserContextHolder.set(loginUser);
            } catch (JwtException | IllegalArgumentException e) {
                // token 无效：不设置认证，后续由 Security 返回 401
                log.debug("JWT 解析失败: {}", e.getMessage());
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
        }
    }
}
