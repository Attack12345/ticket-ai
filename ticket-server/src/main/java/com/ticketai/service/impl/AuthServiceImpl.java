package com.ticketai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketai.common.exception.BusinessException;
import com.ticketai.common.exception.ErrorCode;
import com.ticketai.dto.LoginDTO;
import com.ticketai.dto.RefreshDTO;
import com.ticketai.entity.SysPermissionDO;
import com.ticketai.entity.SysRoleDO;
import com.ticketai.entity.SysRolePermissionDO;
import com.ticketai.entity.SysUserDO;
import com.ticketai.entity.SysUserRoleDO;
import com.ticketai.mapper.SysPermissionMapper;
import com.ticketai.mapper.SysRoleMapper;
import com.ticketai.mapper.SysRolePermissionMapper;
import com.ticketai.mapper.SysUserMapper;
import com.ticketai.mapper.SysUserRoleMapper;
import com.ticketai.security.JwtTokenProvider;
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
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public LoginVO login(LoginDTO dto) {
        SysUserDO user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUserDO>()
                .eq(SysUserDO::getUsername, dto.getUsername()));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已禁用");
        }

        List<String> permissions = loadPermissions(user.getId());
        String accessToken = tokenProvider.createAccessToken(user.getId(), user.getUsername(), permissions);
        String refreshToken = tokenProvider.createRefreshToken(user.getId(), user.getUsername());
        storeRefreshToken(user.getId(), refreshToken);
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
        Long userId = Long.valueOf(claims.getSubject());

        // Lua 原子：校验当前 token 与 Redis 一致才删除（单设备滚动替换语义）
        Long deleted = stringRedisTemplate.execute(
                COMPARE_AND_DELETE, List.of(REFRESH_KEY_PREFIX + userId), dto.getRefreshToken());
        if (deleted == null || deleted != 1L) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "refreshToken 已失效，请重新登录");
        }

        SysUserDO user = sysUserMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在或已禁用");
        }

        List<String> permissions = loadPermissions(userId);
        String accessToken = tokenProvider.createAccessToken(userId, user.getUsername(), permissions);
        String refreshToken = tokenProvider.createRefreshToken(userId, user.getUsername());
        storeRefreshToken(userId, refreshToken);
        return new LoginVO(accessToken, refreshToken);
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

    private void storeRefreshToken(Long userId, String refreshToken) {
        stringRedisTemplate.opsForValue().set(
                REFRESH_KEY_PREFIX + userId, refreshToken,
                tokenProvider.getRefreshExpireMs(), TimeUnit.MILLISECONDS);
    }
}
