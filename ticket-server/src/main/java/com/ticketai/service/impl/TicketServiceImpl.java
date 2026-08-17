package com.ticketai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ticketai.common.PageResult;
import com.ticketai.common.exception.BusinessException;
import com.ticketai.common.exception.ErrorCode;
import com.ticketai.common.util.TicketNoGenerator;
import com.ticketai.dto.AcceptCategoryDTO;
import com.ticketai.dto.TicketCreateDTO;
import com.ticketai.dto.TicketReplyDTO;
import com.ticketai.entity.AgentDO;
import com.ticketai.entity.TicketCommentDO;
import com.ticketai.entity.TicketDO;
import com.ticketai.entity.TicketStatusLogDO;
import com.ticketai.mapper.AgentMapper;
import com.ticketai.mapper.TicketCommentMapper;
import com.ticketai.mapper.TicketMapper;
import com.ticketai.mapper.TicketStatusLogMapper;
import com.ticketai.query.TicketQuery;
import com.ticketai.security.LoginUser;
import com.ticketai.security.UserContextHolder;
import com.ticketai.event.SlaTimeoutEvent;
import com.ticketai.event.TicketCreatedEvent;
import com.ticketai.service.SlaService;
import com.ticketai.service.TicketService;
import com.ticketai.state.StateMachine;
import com.ticketai.state.TicketEvent;
import com.ticketai.state.TicketStatus;
import com.ticketai.state.Transition;
import com.ticketai.vo.TicketStatusLogVO;
import com.ticketai.vo.TicketVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 工单服务（DEV_DOC §5.1.4）。
 * 状态变更唯一入口 transition：状态机校验 → 权限校验 → 乐观锁 → 状态日志 → 事件后置动作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private final TicketMapper ticketMapper;
    private final TicketStatusLogMapper ticketStatusLogMapper;
    private final TicketNoGenerator ticketNoGenerator;
    private final SlaService slaService;
    private final ApplicationEventPublisher eventPublisher;
    private final RedissonClient redissonClient;
    private final AgentMapper agentMapper;
    private final TicketCommentMapper ticketCommentMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDO create(TicketCreateDTO dto) {
        LocalDateTime now = LocalDateTime.now();
        TicketDO ticket = new TicketDO();
        ticket.setTicketNo(ticketNoGenerator.next());
        ticket.setChannelId(dto.getChannelId());
        ticket.setTitle(dto.getTitle());
        ticket.setDescription(dto.getDescription());
        ticket.setCategory(dto.getCategory());
        ticket.setPriority(dto.getPriority() == null ? 3 : dto.getPriority());
        ticket.setStatus(TicketStatus.PENDING_ASSIGN.getCode());
        ticket.setCustomerName(dto.getCustomerName());
        ticket.setCustomerContact(dto.getCustomerContact());
        ticket.setCreateBy(currentUsername());
        ticket.setCreateTime(now);
        ticket.setUpdateTime(now);
        ticketMapper.insert(ticket);

        writeStatusLog(ticket.getId(), TicketStatus.NEW, TicketStatus.PENDING_ASSIGN,
                TicketEvent.SUBMIT, currentUserId(), "USER", "工单创建");
        // SLA 计时启动（M3）
        slaService.createTicketSla(ticket.getId(), ticket.getPriority());
        // 事务提交后发布创建事件（自动分派等异步链路，保证数据可见）；无事务环境（单测）直接发布
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishEvent(new TicketCreatedEvent(ticket));
                }
            });
        } else {
            eventPublisher.publishEvent(new TicketCreatedEvent(ticket));
        }
        log.info("工单创建: id={}, ticketNo={}", ticket.getId(), ticket.getTicketNo());
        return ticket;
    }

    /** SLA 超时升级事件 → 状态机 SYSTEM 事件流转 */
    @EventListener
    @Transactional(rollbackFor = Exception.class)
    public void onSlaTimeout(SlaTimeoutEvent event) {
        transition(event.ticketId(), TicketEvent.TIMEOUT_ESCALATE, null, "SYSTEM");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDO claim(Long ticketId) {
        LoginUser user = UserContextHolder.get();
        if (user == null || user.getAgentId() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "非坐席账号不能领取工单");
        }
        // 1. Redis 分布式锁（Redisson，lease 10s，等待 3s）
        RLock lock = redissonClient.getLock("ticket:claim:" + ticketId);
        boolean locked;
        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFY);
        }
        if (!locked) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFY, "工单正在被其他坐席处理，请稍后重试");
        }
        try {
            TicketDO ticket = requireTicket(ticketId);
            TicketStatus from = TicketStatus.byCode(ticket.getStatus());
            if (!StateMachine.canTransition(from, TicketEvent.CLAIM)) {
                throw new BusinessException(ErrorCode.ILLEGAL_TRANSITION,
                        String.format("当前状态[%s]不允许领取", from.getDesc()));
            }
            // 2. 显式乐观锁 + 状态 + 无主三条件（双保险，影响 0 行 = 已被抢）
            LocalDateTime now = LocalDateTime.now();
            UpdateWrapper<TicketDO> update = new UpdateWrapper<TicketDO>()
                    .eq("id", ticketId)
                    .eq("status", TicketStatus.PENDING_ASSIGN.getCode())
                    .eq("version", ticket.getVersion())
                    .isNull("agent_id")
                    .set("status", TicketStatus.PROCESSING.getCode())
                    .set("agent_id", user.getAgentId())
                    .set("group_id", null)
                    .set("version", ticket.getVersion() + 1)
                    .set("update_by", user.getUsername())
                    .set("update_time", now);
            int rows = ticketMapper.update(null, update);
            if (rows == 0) {
                throw new BusinessException(ErrorCode.CONCURRENT_MODIFY, "工单已被其他坐席领取");
            }
            // 3. 状态日志 + 负载计数
            writeStatusLog(ticketId, from, TicketStatus.PROCESSING, TicketEvent.CLAIM,
                    user.getUserId(), "USER", null);
            agentMapper.update(null, new UpdateWrapper<AgentDO>()
                    .eq("id", user.getAgentId())
                    .setSql("current_load = current_load + 1"));
            // 4. 内存对象同步
            ticket.setStatus(TicketStatus.PROCESSING.getCode());
            ticket.setAgentId(user.getAgentId());
            ticket.setGroupId(null);
            ticket.setVersion(ticket.getVersion() + 1);
            ticket.setUpdateTime(now);
            log.info("坐席抢单成功: ticketId={}, agentId={}", ticketId, user.getAgentId());
            return ticket;
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDO reply(Long ticketId, TicketReplyDTO dto) {
        TicketDO ticket = transition(ticketId, TicketEvent.REPLY, currentUserId(), "USER");
        TicketCommentDO comment = new TicketCommentDO();
        comment.setTicketId(ticketId);
        LoginUser user = UserContextHolder.get();
        comment.setAgentId(user == null ? null : user.getAgentId());
        comment.setType("REPLY");
        comment.setContent(dto.getContent());
        comment.setVisibility(dto.getVisibility() == null ? "ALL" : dto.getVisibility());
        comment.setCreateBy(currentUsername());
        comment.setCreateTime(LocalDateTime.now());
        comment.setUpdateTime(LocalDateTime.now());
        ticketCommentMapper.insert(comment);
        log.info("工单回复: ticketId={}, visibility={}", ticketId, comment.getVisibility());
        return ticket;
    }

    @Override
    public void acceptCategory(Long ticketId, AcceptCategoryDTO dto) {
        requireTicket(ticketId);
        UpdateWrapper<TicketDO> update = new UpdateWrapper<TicketDO>()
                .eq("id", ticketId)
                .set("category", dto.getCategory())
                .set("priority", dto.getPriority())
                .set("update_time", LocalDateTime.now());
        ticketMapper.update(null, update);
        log.info("采纳分类: ticketId={}, category={}, priority={}", ticketId, dto.getCategory(), dto.getPriority());
    }

    @Override
    public PageResult<TicketVO> pageList(TicketQuery query) {
        LambdaQueryWrapper<TicketDO> wrapper = new LambdaQueryWrapper<TicketDO>()
                .eq(query.getStatus() != null, TicketDO::getStatus, query.getStatus())
                .eq(query.getPriority() != null, TicketDO::getPriority, query.getPriority())
                .eq(query.getCategory() != null, TicketDO::getCategory, query.getCategory())
                .eq(query.getAgentId() != null, TicketDO::getAgentId, query.getAgentId())
                .ge(query.getStartTime() != null, TicketDO::getCreateTime, query.getStartTime())
                .le(query.getEndTime() != null, TicketDO::getCreateTime, query.getEndTime())
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(), w -> w
                        .like(TicketDO::getTitle, query.getKeyword())
                        .or()
                        .like(TicketDO::getDescription, query.getKeyword()));
        applySort(wrapper, query.getSort());

        Page<TicketDO> page = ticketMapper.selectPage(query.toPage(), wrapper);
        List<TicketVO> vos = page.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(vos, page.getTotal(), query.getPage(), query.getSize());
    }

    @Override
    public TicketVO getDetail(Long id) {
        return toVO(requireTicket(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDO transition(Long ticketId, TicketEvent event, Long operatorId, String operatorType) {
        return transition(ticketId, event, operatorId, operatorType, null, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDO transition(Long ticketId, TicketEvent event, Long operatorId, String operatorType,
                               Long agentId, Long groupId, String assignStrategy) {
        TicketDO ticket = requireTicket(ticketId);
        TicketStatus from = TicketStatus.byCode(ticket.getStatus());

        // 1. 状态机校验
        Transition transition = StateMachine.getTransition(from, event);
        if (transition == null) {
            throw new BusinessException(ErrorCode.ILLEGAL_TRANSITION,
                    String.format("当前状态[%s]不允许事件[%s]", from.getDesc(), event.getDesc()));
        }

        // 2. 权限校验（SYSTEM 事件仅系统内部触发，跳过用户权限检查）
        if (!transition.isSystemOnly()) {
            checkPermission(transition.requiredPermission());
        }

        // 3. 乐观锁更新：显式 WHERE version + 状态双条件（MP @Version 拦截器在
        //    version=0 的实体更新路径存在参数注入缺陷，故用 UpdateWrapper 显式实现；
        //    不用 LambdaUpdateWrapper：其 lambda 解析依赖 MP 运行时缓存，纯单测环境不可用）
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<TicketDO> update = new UpdateWrapper<TicketDO>()
                .eq("id", ticketId)
                .eq("version", ticket.getVersion())
                .eq("status", from.getCode())
                .set("status", transition.to().getCode())
                .set("version", ticket.getVersion() + 1)
                .set("update_by", currentUsername())
                .set("update_time", now);
        // 事件后置动作并入同一条 UPDATE（DEV_DOC §5.1.5 时间戳结算部分）
        if (event == TicketEvent.REPLY && ticket.getFirstRespondedAt() == null) {
            update.set("first_responded_at", now);
        } else if (event == TicketEvent.RESOLVE) {
            update.set("resolved_at", now);
        } else if (event == TicketEvent.CLOSE || event == TicketEvent.CANCEL) {
            update.set("closed_at", now);
        } else if (event == TicketEvent.REOPEN) {
            update.set("resolved_at", null).set("closed_at", null);
        } else if ((event == TicketEvent.AUTO_ASSIGN || event == TicketEvent.MANUAL_ASSIGN) && agentId != null) {
            update.set("agent_id", agentId);
            if (groupId != null) {
                update.set("group_id", groupId);
            }
            if (assignStrategy != null) {
                update.set("assign_strategy", assignStrategy);
            }
        }
        int rows = ticketMapper.update(null, update);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFY);
        }
        // 同步内存对象（返回给调用方，含后置动作时间戳）
        ticket.setStatus(transition.to().getCode());
        ticket.setVersion(ticket.getVersion() + 1);
        ticket.setUpdateTime(now);
        if (event == TicketEvent.REPLY && ticket.getFirstRespondedAt() == null) {
            ticket.setFirstRespondedAt(now);
        } else if (event == TicketEvent.RESOLVE) {
            ticket.setResolvedAt(now);
        } else if (event == TicketEvent.CLOSE || event == TicketEvent.CANCEL) {
            ticket.setClosedAt(now);
        } else if (event == TicketEvent.REOPEN) {
            ticket.setResolvedAt(null);
            ticket.setClosedAt(null);
        } else if ((event == TicketEvent.AUTO_ASSIGN || event == TicketEvent.MANUAL_ASSIGN) && agentId != null) {
            ticket.setAgentId(agentId);
            ticket.setGroupId(groupId);
            ticket.setAssignStrategy(assignStrategy);
        }

        // 4. 状态日志
        writeStatusLog(ticketId, from, transition.to(), event, operatorId, operatorType, null);

        log.info("工单流转: id={}, {} --{}--> {}", ticketId, from.getDesc(), event.getDesc(), transition.to().getDesc());
        return ticket;
    }

    @Override
    public List<TicketStatusLogVO> timeline(Long ticketId) {
        requireTicket(ticketId);
        return ticketStatusLogMapper.selectList(new LambdaQueryWrapper<TicketStatusLogDO>()
                        .eq(TicketStatusLogDO::getTicketId, ticketId)
                        .orderByAsc(TicketStatusLogDO::getCreateTime)).stream()
                .map(this::toLogVO).toList();
    }

    // ---------- 私有方法 ----------

    private TicketDO requireTicket(Long id) {
        TicketDO ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工单不存在: id=" + id);
        }
        return ticket;
    }

    private void checkPermission(String permission) {
        LoginUser user = UserContextHolder.get();
        if (user == null || user.getPermissions() == null || !user.getPermissions().contains(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "缺少权限: " + permission);
        }
    }

    private void writeStatusLog(Long ticketId, TicketStatus from, TicketStatus to,
                                TicketEvent event, Long operatorId, String operatorType, String remark) {
        TicketStatusLogDO logEntry = new TicketStatusLogDO();
        logEntry.setTicketId(ticketId);
        logEntry.setFromStatus(from == null ? null : from.getCode());
        logEntry.setToStatus(to.getCode());
        logEntry.setEvent(event.name());
        logEntry.setOperatorId(operatorId);
        logEntry.setOperatorType(operatorType);
        logEntry.setRemark(remark);
        logEntry.setCreateTime(LocalDateTime.now());
        ticketStatusLogMapper.insert(logEntry);
    }

    /** 事件后置动作已并入 transition 的单条 UPDATE（时间戳结算），无独立方法 */

    private void applySort(LambdaQueryWrapper<TicketDO> wrapper, String sort) {
        if (sort == null || sort.isBlank()) {
            wrapper.orderByDesc(TicketDO::getCreateTime);
            return;
        }
        String[] parts = sort.split(":");
        String field = parts[0];
        boolean asc = parts.length > 1 && "asc".equalsIgnoreCase(parts[1]);
        switch (field) {
            case "updateTime" -> {
                if (asc) {
                    wrapper.orderByAsc(TicketDO::getUpdateTime);
                } else {
                    wrapper.orderByDesc(TicketDO::getUpdateTime);
                }
            }
            default -> {
                if (asc) {
                    wrapper.orderByAsc(TicketDO::getCreateTime);
                } else {
                    wrapper.orderByDesc(TicketDO::getCreateTime);
                }
            }
        }
    }

    private TicketVO toVO(TicketDO ticket) {
        TicketVO vo = new TicketVO();
        vo.setId(ticket.getId());
        vo.setTicketNo(ticket.getTicketNo());
        vo.setTitle(ticket.getTitle());
        vo.setDescription(ticket.getDescription());
        vo.setCategory(ticket.getCategory());
        vo.setPriority(ticket.getPriority());
        vo.setStatus(ticket.getStatus());
        vo.setStatusText(TicketStatus.byCode(ticket.getStatus()).getDesc());
        vo.setGroupId(ticket.getGroupId());
        vo.setAgentId(ticket.getAgentId());
        vo.setAssignStrategy(ticket.getAssignStrategy());
        vo.setCustomerName(ticket.getCustomerName());
        vo.setCustomerContact(ticket.getCustomerContact());
        vo.setSlaPolicyId(ticket.getSlaPolicyId());
        vo.setFirstResponseDeadline(ticket.getFirstResponseDeadline());
        vo.setResolveDeadline(ticket.getResolveDeadline());
        vo.setFirstRespondedAt(ticket.getFirstRespondedAt());
        vo.setResolvedAt(ticket.getResolvedAt());
        vo.setClosedAt(ticket.getClosedAt());
        vo.setAiCategory(ticket.getAiCategory());
        vo.setAiPriority(ticket.getAiPriority());
        vo.setAiScore(ticket.getAiScore());
        vo.setCreateTime(ticket.getCreateTime());
        vo.setUpdateTime(ticket.getUpdateTime());
        vo.setAllowedEvents(StateMachine.allowedEvents(TicketStatus.byCode(ticket.getStatus())).stream().toList());
        return vo;
    }

    private TicketStatusLogVO toLogVO(TicketStatusLogDO logEntry) {
        TicketStatusLogVO vo = new TicketStatusLogVO();
        vo.setId(logEntry.getId());
        vo.setFromStatus(logEntry.getFromStatus());
        vo.setFromStatusText(logEntry.getFromStatus() == null ? null
                : TicketStatus.byCode(logEntry.getFromStatus()).getDesc());
        vo.setToStatus(logEntry.getToStatus());
        vo.setToStatusText(TicketStatus.byCode(logEntry.getToStatus()).getDesc());
        vo.setEvent(logEntry.getEvent());
        vo.setOperatorId(logEntry.getOperatorId());
        vo.setOperatorType(logEntry.getOperatorType());
        vo.setRemark(logEntry.getRemark());
        vo.setCreateTime(logEntry.getCreateTime());
        return vo;
    }

    private Long currentUserId() {
        LoginUser user = UserContextHolder.get();
        return user == null ? null : user.getUserId();
    }

    private String currentUsername() {
        LoginUser user = UserContextHolder.get();
        return user == null ? null : user.getUsername();
    }
}
