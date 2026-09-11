package com.ticketai.service.impl;

import com.ticketai.dto.RefreshDTO;
import com.ticketai.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-7 认证安全测试：登出吊销 access（jti 黑名单）+ delete refresh（SHA-256 哈希比对）。
 * 全部 mock，不连接真实中间件。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private com.ticketai.mapper.SysUserMapper sysUserMapper;
    @Mock
    private com.ticketai.mapper.SysUserRoleMapper sysUserRoleMapper;
    @Mock
    private com.ticketai.mapper.SysRoleMapper sysRoleMapper;
    @Mock
    private com.ticketai.mapper.SysRolePermissionMapper sysRolePermissionMapper;
    @Mock
    private com.ticketai.mapper.SysPermissionMapper sysPermissionMapper;
    @Mock
    private com.ticketai.mapper.AgentMapper agentMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider tokenProvider;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private com.ticketai.service.AuditService auditService;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(sysUserMapper, sysUserRoleMapper, sysRoleMapper,
                sysRolePermissionMapper, sysPermissionMapper, agentMapper,
                passwordEncoder, tokenProvider, stringRedisTemplate, auditService);
    }

    private Claims accessClaims() {
        Claims claims = mock(Claims.class);
        when(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class)).thenReturn(JwtTokenProvider.TYPE_ACCESS);
        when(claims.getId()).thenReturn("jti-access-001");
        when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 60_000));
        return claims;
    }

    private Claims refreshClaims() {
        Claims claims = mock(Claims.class);
        when(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class)).thenReturn(JwtTokenProvider.TYPE_REFRESH);
        when(claims.getSubject()).thenReturn("1");
        return claims;
    }

    @Test
    @DisplayName("P0-7：登出时 access 的 jti 进黑名单（TTL=剩余有效期），refresh 走 Lua 删除")
    void logoutBlacklistsAccessAndDeletesRefresh() {
        Claims access = accessClaims();
        Claims refresh = refreshClaims();
        when(tokenProvider.parse("access-tok")).thenReturn(access);
        when(tokenProvider.parse("refresh-tok")).thenReturn(refresh);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(1L);

        RefreshDTO dto = new RefreshDTO();
        dto.setRefreshToken("refresh-tok");
        assertDoesNotThrow(() -> authService.logout("Bearer access-tok", dto));

        // 1. jti 置入黑名单，TTL 毫秒
        verify(valueOperations).set(org.mockito.ArgumentMatchers.startsWith(JwtTokenProvider.BLACKLIST_KEY_PREFIX),
                org.mockito.ArgumentMatchers.eq("1"),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS));
        // 2. Lua compare-and-delete 携带 refresh token（参数为 SHA-256 摘要，64 位十六进制）
        ArgumentCaptor<Object> argCaptor = ArgumentCaptor.forClass(Object.class);
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), anyList(), argCaptor.capture());
        String hashed = (String) argCaptor.getValue();
        assertEquals(64, hashed.length(), "refresh token 应存 SHA-256 摘要而非明文");
        assertEquals(hashed.toLowerCase(), hashed, "摘要应为小写十六进制");
    }

    @Test
    @DisplayName("P0-7：无效/过期 token 登出是 no-op，不抛业务异常")
    void logoutWithInvalidTokensIsNoOp() {
        when(tokenProvider.parse(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new JwtException("expired"));

        RefreshDTO dto = new RefreshDTO();
        dto.setRefreshToken("stale-refresh");
        assertDoesNotThrow(() -> authService.logout("Bearer stale-access", dto));

        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("P0-7：refresh 类型 token 冒充 access（放 Authorization）不会进黑名单")
    void refreshTokenCannotTriggerBlacklist() {
        Claims refresh = refreshClaims(); // type=refresh
        when(tokenProvider.parse("refresh-tok")).thenReturn(refresh);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(1L);

        RefreshDTO dto = new RefreshDTO();
        dto.setRefreshToken("refresh-tok");
        // 把 refresh token 塞进 Authorization 冒充 access：不应写黑名单，仅删 refresh
        assertDoesNotThrow(() -> authService.logout("Bearer refresh-tok", dto));

        verify(stringRedisTemplate, never()).opsForValue();
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), anyList(), any());
    }
}