package com.ticketai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketai.common.exception.BusinessException;
import com.ticketai.common.exception.ErrorCode;
import com.ticketai.dto.LoginDTO;
import com.ticketai.dto.RefreshDTO;
import com.ticketai.entity.AgentDO;
import com.ticketai.entity.SysPermissionDO;
import com.ticketai.entity.SysRoleDO;
import com.ticketai.entity.SysRolePermissionDO;
import com.ticketai.entity.SysUserDO;
import com.ticketai.entity.SysUserRoleDO;
import com.ticketai.mapper.AgentMapper;
import com.ticketai.mapper.SysPermissionMapper;
import com.ticketai.mapper.SysRoleMapper;
import com.ticketai.mapper.SysRolePermissionMapper;
import com.ticketai.mapper.SysUserMapper;
import com.ticketai.mapper.SysUserRoleMapper;
import com.ticketai.security.JwtTokenProvider;
import com.ticketai.service.AuditService;
import com.ticketai.service.AuthService;
import com.ticketai.vo.LoginVO;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务（DEV_DOC §5.6）。
 * refresh 校验与替换用 Lua 原子操作，防止并发刷新双成功。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String REFRESH_KEY_PREFIX = "refresh:";

    /**
     * Lua：compare-and-delete。value 与 Redis 中一致则删除并返回 1，否则返回 0。
     */
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysPermissionMapper sysPermissionMapper;
    private final AgentMapper agentMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final StringRedisTemplate stringRedisTemplate;
    private final AuditService auditService;

    @Override
    public LoginVO login(LoginDTO dto) {
        SysUserDO user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUserDO>()
                .eq(SysUserDO::getUsername, dto.getUsername()));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            // P1-9：登录失败必须落审计（账号不可用/口令错误）——对不存在的用户也记录，避免暴露账号是否存在
            auditService.record("LOGIN_FAILED", "LOGIN", null, dto.getUsername(), null);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            auditService.record("LOGIN_FAILED", "LOGIN", user.getId(), dto.getUsername(), "{\"reason\":\"账号禁用\"}");
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已禁用");
        }

        List<String> permissions = loadPermissions(user.getId());
        Long agentId = loadAgentId(user.getId());
        String accessToken = tokenProvider.createAccessToken(user.getId(), user.getUsername(), agentId, permissions);
        String refreshToken = tokenProvider.createRefreshToken(user.getId(), user.getUsername());
        storeRefreshToken(user.getId(), refreshToken);
        auditService.record("LOGIN", "LOGIN", user.getId(), user.getUsername(), null);
        return new LoginVO(accessToken, refreshToken);
    }

    @Override
    public LoginVO refresh(RefreshDTO dto) {
        Claims claims;
        try {
            claims = tokenProvider.parse(dto.getRefreshToken());
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "refreshToken 无效或已过期");
        }
        // M-1/P0-7：仅接受 refresh 类型 token
        if (!JwtTokenProvider.TYPE_REFRESH.equals(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class))) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "refreshToken 类型错误");
        }
        Long userId = Long.valueOf(claims.getSubject());

        // Lua 原子：校验当前 token 哈希与 Redis 一致才删除（单设备滚动替换语义）
        Long deleted = stringRedisTemplate.execute(
                COMPARE_AND_DELETE, List.of(REFRESH_KEY_PREFIX + userId), sha256Hex(dto.getRefreshToken()));
        if (deleted == null || deleted != 1L) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "refreshToken 已失效，请重新登录");
        }

        SysUserDO user = sysUserMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在或已禁用");
        }

        List<String> permissions = loadPermissions(userId);
        Long agentId = loadAgentId(userId);
        String accessToken = tokenProvider.createAccessToken(userId, user.getUsername(), agentId, permissions);
        String refreshToken = tokenProvider.createRefreshToken(userId, user.getUsername());
        storeRefreshToken(userId, refreshToken);
        return new LoginVO(accessToken, refreshToken);
    }

    /**
     * M-6.1：登出。access token 的 jti 进黑名单（TTL=剩余有效期），refresh token 删除。
     * 尽力而为且幂等：任何 token 已失效/过期/缺失都不会抛异常。
     */
    @Override
    public void logout(String authorization, RefreshDTO dto) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String access = authorization.substring(7).trim();
            if (!access.isEmpty()) {
                try {
                    Claims claims = tokenProvider.parse(access);
                    if (JwtTokenProvider.TYPE_ACCESS.equals(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class))) {
                        blacklistJti(claims);
                    }
                } catch (JwtException | IllegalArgumentException e) {
                    log.debug("logout 吊销 access 跳过（无效/已过期）: {}", e.getMessage());
                }
            }
        }
        if (dto != null && dto.getRefreshToken() != null && !dto.getRefreshToken().isBlank()) {
            try {
                Claims claims = tokenProvider.parse(dto.getRefreshToken());
                if (JwtTokenProvider.TYPE_REFRESH.equals(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class))) {
                    Long userId = Long.valueOf(claims.getSubject());
                    Long deleted = stringRedisTemplate.execute(
                            COMPARE_AND_DELETE, List.of(REFRESH_KEY_PREFIX + userId), sha256Hex(dto.getRefreshToken()));
                    if (deleted != null && deleted == 1L) {
                        log.info("用户登出，refresh token 已删除: userId={}", userId);
                        auditService.record("LOGOUT", "LOGIN", userId, null, null);
                    }
                }
            } catch (JwtException | IllegalArgumentException e) {
                log.debug("logout 处理 refreshToken 跳过（无效/已过期）: {}", e.getMessage());
            }
        }
    }

    /** 将 jti 置入黑名单，TTL 为 token 剩余有效期（期间内该 access token 不再可用） */
    private void blacklistJti(Claims claims) {
        String jti = claims.getId();
        Date exp = claims.getExpiration();
        if (jti == null || exp == null) {
            return;
        }
        long ttl = exp.getTime() - System.currentTimeMillis();
        if (ttl <= 0) {
            return; // 已自然过期，无需黑名单
        }
        stringRedisTemplate.opsForValue().set(
                JwtTokenProvider.BLACKLIST_KEY_PREFIX + jti, "1", ttl, TimeUnit.MILLISECONDS);
    }

    /** 查询用户对应的坐席档案 ID（非坐席返回 null） */
    private Long loadAgentId(Long userId) {
        AgentDO agent = agentMapper.selectOne(new LambdaQueryWrapper<AgentDO>().eq(AgentDO::getUserId, userId));
        return agent == null ? null : agent.getId();
    }

    /** 加载用户权限码：user → roles → permissions */
    private List<String> loadPermissions(Long userId) {
        List<Long> roleIds = sysUserRoleMapper.selectList(new LambdaQueryWrapper<SysUserRoleDO>()
                        .eq(SysUserRoleDO::getUserId, userId)).stream()
                .map(SysUserRoleDO::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> permissionIds = sysRolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermissionDO>()
                        .in(SysRolePermissionDO::getRoleId, roleIds)).stream()
                .map(SysRolePermissionDO::getPermissionId).distinct().toList();
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        return sysPermissionMapper.selectList(new LambdaQueryWrapper<SysPermissionDO>()
                        .in(SysPermissionDO::getId, permissionIds)).stream()
                .map(SysPermissionDO::getCode).toList();
    }

    /**
     * M-6.1：Redis 仅存 refresh token 的 SHA-256 摘要。
     * 明文 JWT 泄库也无法被用于伪造刷新（存储层泄露降级为无害）。
     */
    private void storeRefreshToken(Long userId, String refreshToken) {
        stringRedisTemplate.opsForValue().set(
                REFRESH_KEY_PREFIX + userId, sha256Hex(refreshToken),
                tokenProvider.getRefreshExpireMs(), TimeUnit.MILLISECONDS);
    }

    private String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
