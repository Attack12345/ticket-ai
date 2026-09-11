package com.ticketai.service.impl;

import com.ticketai.entity.AuditLogDO;
import com.ticketai.mapper.AuditLogMapper;
import com.ticketai.security.LoginUser;
import com.ticketai.security.UserContextHolder;
import com.ticketai.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * 审计服务实现（P1-9，docs/enterprise-production-gaps.md）。
 * REQUIRES_NEW：审计独享事务提交——即使业务事务回滚，审计记录仍保留。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final AuditLogMapper auditLogMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String targetType, Long targetId, String username, String detail) {
        try {
            LoginUser user = UserContextHolder.get();
            AuditLogDO audit = new AuditLogDO();
            audit.setUserId(user == null ? null : user.getUserId());
            audit.setUsername(username != null ? username : (user == null ? null : user.getUsername()));
            audit.setAction(action);
            audit.setTargetType(targetType);
            audit.setTargetId(targetId);
            audit.setDetailJson(detail);
            audit.setIp(resolveClientIp());
            audit.setCreateTime(LocalDateTime.now());
            auditLogMapper.insert(audit);
        } catch (Exception e) {
            // 审计失败不得影响业务主链路，仅告警
            log.error("审计写入失败: action={}, targetType={}, targetId={}", action, targetType, targetId, e);
        }
    }

    /** 客户端 IP：优先 X-Forwarded-For（代理/nginx 场景），否则 remoteAddr */
    private String resolveClientIp() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                HttpServletRequest request = attrs.getRequest();
                String xff = request.getHeader("X-Forwarded-For");
                if (xff != null && !xff.isBlank()) {
                    return xff.split(",")[0].trim();
                }
                return request.getRemoteAddr();
            }
        } catch (Exception e) {
            log.debug("审计 IP 解析失败，置空", e);
        }
        return null;
    }
}