package com.ticketai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
import com.ticketai.state.StateMachine;
import com.ticketai.state.TicketEvent;
import com.ticketai.state.TicketStatus;
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
            try {
                handleDelayCheck(new SlaMessage(sla.getTicketId(), sla.getId(),
                        SlaMessage.TYPE_FIRST_RESPONSE, sla.getFirstResponseDeadline()));
            } catch (Exception e) {
                // 逐条隔离：单条 SLA 异常不得拖垮整批补偿（合法性预判已前置，此处为兜底）
                log.error("补偿扫描单条失败（已隔离）: slaId={}, ticketId={}",
                        sla.getId(), sla.getTicketId(), e);
            }
        }
        // 解决超时未升级（未解决状态）
        List<TicketSlaDO> resolveOverdue = ticketSlaMapper.selectList(new LambdaQueryWrapper<TicketSlaDO>()
                .eq(TicketSlaDO::getEscalationTriggered, 0)
                .eq(TicketSlaDO::getResolveStatus, 0)
                .lt(TicketSlaDO::getResolveDeadline, now));
        for (TicketSlaDO sla : resolveOverdue) {
            log.warn("补偿扫描：解决超时未升级，ticketId={}", sla.getTicketId());
            try {
                handleDelayCheck(new SlaMessage(sla.getTicketId(), sla.getId(),
                        SlaMessage.TYPE_RESOLVE, sla.getResolveDeadline()));
            } catch (Exception e) {
                log.error("补偿扫描单条失败（已隔离）: slaId={}, ticketId={}",
                        sla.getId(), sla.getTicketId(), e);
            }
        }
    }

    /** 结算为"按时"（仅在未结算时标记，条件更新保证原子） */
    private void markSettled(TicketSlaDO sla, String field, int status) {
        LocalDateTime now = LocalDateTime.now();
        if ("firstResponseStatus".equals(field)) {
            int rows = ticketSlaMapper.update(null, new UpdateWrapper<TicketSlaDO>()
                    .eq("id", sla.getId())
                    .eq("first_response_status", 0)
                    .set("first_response_status", status)
                    .set("first_responded_at",
                            sla.getFirstRespondedAt() != null ? sla.getFirstRespondedAt() : now)
                    .set("update_time", now));
            if (rows > 0) {
                sla.setFirstResponseStatus(status);
                sla.setFirstRespondedAt(sla.getFirstRespondedAt() != null ? sla.getFirstRespondedAt() : now);
            }
        } else if ("resolveStatus".equals(field)) {
            int rows = ticketSlaMapper.update(null, new UpdateWrapper<TicketSlaDO>()
                    .eq("id", sla.getId())
                    .eq("resolve_status", 0)
                    .set("resolve_status", status)
                    .set("resolved_at", sla.getResolvedAt() != null ? sla.getResolvedAt() : now)
                    .set("update_time", now));
            if (rows > 0) {
                sla.setResolveStatus(status);
                sla.setResolvedAt(sla.getResolvedAt() != null ? sla.getResolvedAt() : now);
            }
        }
    }

    /**
     * 触发升级：原子条件占位 + 审计 + 状态机预判后发布事件。
     * <p>防双重升级：UPDATE ... WHERE escalation_triggered=0 AND version=?，影响 0 行即并发已升级。
     * <p>防补偿毒化：工单处于 RESOLVED/CLOSED/CANCELLED 等未注册 TIMEOUT_ESCALATE 的状态时，
     * 只结算 SLA 与落审计，不触发状态机事件——否则 ILLEGAL_TRANSITION 会回滚整批补偿
     * （见 docs/enterprise-production-gaps.md P0-4）。
     */
    private void escalate(TicketDO ticket, TicketSlaDO sla, String reason) {
        if (sla.getEscalationTriggered() != null && sla.getEscalationTriggered() == 1) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int expectVersion = sla.getVersion() == null ? 0 : sla.getVersion();
        int rows = ticketSlaMapper.update(null, new UpdateWrapper<TicketSlaDO>()
                .eq("id", sla.getId())
                .eq("escalation_triggered", 0)
                .eq("version", expectVersion)
                .set("escalation_triggered", 1)
                .set("escalated_at", now)
                .set("version", expectVersion + 1)
                .set("update_time", now));
        if (rows == 0) {
            // 并发已升级（多副本补偿/重复消息），幂等丢弃
            return;
        }
        sla.setEscalationTriggered(1);
        sla.setEscalatedAt(now);

        // 升级动作：审计日志（站内信通知为占位，M7 完善）
        AuditLogDO audit = new AuditLogDO();
        audit.setUserId(null);
        audit.setUsername("system");
        audit.setAction("SLA_ESCALATE");
        audit.setTargetType("TICKET");
        audit.setTargetId(ticket.getId());
        audit.setDetailJson("{\"reason\":\"" + reason + "\",\"ticketNo\":\"" + ticket.getTicketNo() + "\"}");
        audit.setCreateTime(now);
        auditLogMapper.insert(audit);

        log.warn("SLA 超时升级标记: ticketId={}, ticketNo={}, reason={}", ticket.getId(), ticket.getTicketNo(), reason);

        // 状态机预判：仅对注册了 TIMEOUT_ESCALATE 的状态发布事件驱动流转
        boolean canEscalate = StateMachine.canTransition(
                TicketStatus.byCode(ticket.getStatus()), TicketEvent.TIMEOUT_ESCALATE);
        if (canEscalate) {
            eventPublisher.publishEvent(new SlaTimeoutEvent(ticket.getId()));
        } else {
            // 工单已解决/关闭/取消等：不触发状态机，仅结算 SLA，杜绝脏批次
            log.warn("SLA 超时但工单状态[{}]不支持 TIMEOUT_ESCALATE，仅标记升级：slaId={}, ticketId={}",
                    ticket.getStatus(), sla.getId(), ticket.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settleOnClosed(Long ticketId) {
        TicketSlaDO sla = ticketSlaMapper.selectOne(new LambdaQueryWrapper<TicketSlaDO>()
                .eq(TicketSlaDO::getTicketId, ticketId));
        if (sla == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (sla.getFirstResponseStatus() == null || sla.getFirstResponseStatus() == 0) {
            int status = sla.getFirstResponseDeadline() != null
                    && sla.getFirstResponseDeadline().isBefore(now) ? 2 : 1;
            int rows = ticketSlaMapper.update(null, new UpdateWrapper<TicketSlaDO>()
                    .eq("id", sla.getId())
                    .eq("first_response_status", 0)
                    .set("first_response_status", status)
                    .set("first_responded_at",
                            sla.getFirstRespondedAt() != null ? sla.getFirstRespondedAt() : now)
                    .set("update_time", now));
            if (rows > 0) {
                sla.setFirstResponseStatus(status);
            }
        }
        if (sla.getResolveStatus() == null || sla.getResolveStatus() == 0) {
            int status = sla.getResolveDeadline() != null
                    && sla.getResolveDeadline().isBefore(now) ? 2 : 1;
            int rows = ticketSlaMapper.update(null, new UpdateWrapper<TicketSlaDO>()
                    .eq("id", sla.getId())
                    .eq("resolve_status", 0)
                    .set("resolve_status", status)
                    .set("resolved_at", now)
                    .set("update_time", now));
            if (rows > 0) {
                sla.setResolveStatus(status);
            }
        }
        log.info("SLA 终止结算: ticketId={}, first=响应{}, resolve={}",
                ticketId, sla.getFirstResponseStatus(), sla.getResolveStatus());
    }

    private long toEpochMs(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
