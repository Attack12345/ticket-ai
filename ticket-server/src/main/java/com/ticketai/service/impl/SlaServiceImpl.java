package com.ticketai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketai.entity.AuditLogDO;
import com.ticketai.entity.SlaPolicyDO;
import com.ticketai.entity.TicketDO;
import com.ticketai.entity.TicketSlaDO;
import com.ticketai.event.SlaTimeoutEvent;
import com.ticketai.mapper.AuditLogMapper;
import com.ticketai.mapper.SlaPolicyMapper;
import com.ticketai.mapper.TicketMapper;
import com.ticketai.mapper.TicketSlaMapper;
import com.ticketai.mq.SlaDelayProducer;
import com.ticketai.mq.SlaMessage;
import com.ticketai.service.SlaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * SLA 引擎核心（DEV_DOC §5.2）：延迟消息结算 + 补偿扫描 + 超时升级。
 * 升级通过 SlaTimeoutEvent 事件触发状态机（解耦，避免循环依赖）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlaServiceImpl implements SlaService {

    private final SlaPolicyMapper slaPolicyMapper;
    private final TicketSlaMapper ticketSlaMapper;
    private final TicketMapper ticketMapper;
    private final AuditLogMapper auditLogMapper;
    private final SlaDelayProducer slaDelayProducer;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createTicketSla(Long ticketId, Integer priority) {
        SlaPolicyDO policy = slaPolicyMapper.selectOne(new LambdaQueryWrapper<SlaPolicyDO>()
                .eq(SlaPolicyDO::getPriority, priority)
                .eq(SlaPolicyDO::getStatus, 1));
        if (policy == null) {
            log.warn("SLA 策略缺失，跳过计时: ticketId={}, priority={}", ticketId, priority);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime firstResponseDeadline = now.plusMinutes(policy.getFirstResponseMinutes());
        LocalDateTime resolveDeadline = now.plusMinutes(policy.getResolveMinutes());

        TicketSlaDO sla = new TicketSlaDO();
        sla.setTicketId(ticketId);
        sla.setSlaPolicyId(policy.getId());
        sla.setFirstResponseDeadline(firstResponseDeadline);
        sla.setResolveDeadline(resolveDeadline);
        sla.setFirstResponseStatus(0);
        sla.setResolveStatus(0);
        sla.setEscalationTriggered(0);
        sla.setCreateTime(now);
        sla.setUpdateTime(now);
        ticketSlaMapper.insert(sla);

        if (policy.getAutoEscalate() != null && policy.getAutoEscalate() == 1) {
            slaDelayProducer.send(ticketId, sla.getId(), SlaMessage.TYPE_FIRST_RESPONSE,
                    toEpochMs(firstResponseDeadline));
            slaDelayProducer.send(ticketId, sla.getId(), SlaMessage.TYPE_RESOLVE,
                    toEpochMs(resolveDeadline));
        }
        log.info("SLA 计时启动: ticketId={}, 响应截止={}, 解决截止={}",
                ticketId, firstResponseDeadline, resolveDeadline);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleDelayCheck(SlaMessage message) {
        TicketSlaDO sla = ticketSlaMapper.selectById(message.slaId());
        if (sla == null) {
            log.warn("SLA 记录不存在，忽略: slaId={}", message.slaId());
            return;
        }
        // 幂等：已触发升级或已结算超时，不再处理
        if (sla.getEscalationTriggered() != null && sla.getEscalationTriggered() == 1) {
            return;
        }
        TicketDO ticket = ticketMapper.selectById(message.ticketId());
        if (ticket == null) {
            return;
        }

        switch (message.checkType()) {
            case SlaMessage.TYPE_FIRST_RESPONSE -> {
                if (sla.getFirstRespondedAt() != null || ticket.getFirstRespondedAt() != null) {
                    markSettled(sla, "firstResponseStatus", 1);
                    return;
                }
                sla.setFirstResponseStatus(2);
                ticketSlaMapper.updateById(sla);
                escalate(ticket, sla, "首次响应超时");
            }
            case SlaMessage.TYPE_RESOLVE -> {
                if (sla.getResolvedAt() != null || ticket.getResolvedAt() != null) {
                    markSettled(sla, "resolveStatus", 1);
                    return;
                }
                sla.setResolveStatus(2);
                ticketSlaMapper.updateById(sla);
                escalate(ticket, sla, "解决超时");
            }
            default -> log.warn("未知 SLA 检查类型: {}", message.checkType());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void compensate() {
        LocalDateTime now = LocalDateTime.now();
        // 响应超时未升级
        List<TicketSlaDO> overdue = ticketSlaMapper.selectList(new LambdaQueryWrapper<TicketSlaDO>()
                .eq(TicketSlaDO::getEscalationTriggered, 0)
                .eq(TicketSlaDO::getFirstResponseStatus, 0)
                .lt(TicketSlaDO::getFirstResponseDeadline, now));
        for (TicketSlaDO sla : overdue) {
            log.warn("补偿扫描：首次响应超时未升级，ticketId={}", sla.getTicketId());
            handleDelayCheck(new SlaMessage(sla.getTicketId(), sla.getId(),
                    SlaMessage.TYPE_FIRST_RESPONSE, sla.getFirstResponseDeadline()));
        }
        // 解决超时未升级（未解决状态）
        List<TicketSlaDO> resolveOverdue = ticketSlaMapper.selectList(new LambdaQueryWrapper<TicketSlaDO>()
                .eq(TicketSlaDO::getEscalationTriggered, 0)
                .eq(TicketSlaDO::getResolveStatus, 0)
                .lt(TicketSlaDO::getResolveDeadline, now));
        for (TicketSlaDO sla : resolveOverdue) {
            log.warn("补偿扫描：解决超时未升级，ticketId={}", sla.getTicketId());
            handleDelayCheck(new SlaMessage(sla.getTicketId(), sla.getId(),
                    SlaMessage.TYPE_RESOLVE, sla.getResolveDeadline()));
        }
    }

    /** 结算为"按时"（仅在未结算时标记） */
    private void markSettled(TicketSlaDO sla, String field, int status) {
        if (field.equals("firstResponseStatus") && sla.getFirstResponseStatus() == 0) {
            sla.setFirstResponseStatus(status);
            sla.setFirstRespondedAt(sla.getFirstRespondedAt() != null
                    ? sla.getFirstRespondedAt() : LocalDateTime.now());
            ticketSlaMapper.updateById(sla);
        } else if (field.equals("resolveStatus") && sla.getResolveStatus() == 0) {
            sla.setResolveStatus(status);
            sla.setResolvedAt(sla.getResolvedAt() != null ? sla.getResolvedAt() : LocalDateTime.now());
            ticketSlaMapper.updateById(sla);
        }
    }

    /** 触发升级：标记 + 审计 + 发布事件（状态机 TIMEOUT_ESCALATE） */
    private void escalate(TicketDO ticket, TicketSlaDO sla, String reason) {
        if (sla.getEscalationTriggered() != null && sla.getEscalationTriggered() == 1) {
            return;
        }
        sla.setEscalationTriggered(1);
        sla.setEscalatedAt(LocalDateTime.now());
        ticketSlaMapper.updateById(sla);

        // 升级动作：审计日志（站内信通知为占位，M7 完善）
        AuditLogDO audit = new AuditLogDO();
        audit.setUserId(null);
        audit.setUsername("system");
        audit.setAction("SLA_ESCALATE");
        audit.setTargetType("TICKET");
        audit.setTargetId(ticket.getId());
        audit.setDetailJson("{\"reason\":\"" + reason + "\",\"ticketNo\":\"" + ticket.getTicketNo() + "\"}");
        audit.setCreateTime(LocalDateTime.now());
        auditLogMapper.insert(audit);

        log.warn("SLA 超时升级: ticketId={}, ticketNo={}, reason={}", ticket.getId(), ticket.getTicketNo(), reason);
        // 事件驱动状态机流转（SYSTEM 事件）
        eventPublisher.publishEvent(new SlaTimeoutEvent(ticket.getId()));
    }

    private long toEpochMs(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
