package com.ticketai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ticketai.common.PageResult;
import com.ticketai.common.exception.BusinessException;
import com.ticketai.common.exception.ErrorCode;
import com.ticketai.common.util.TicketNoGenerator;
import com.ticketai.dto.TicketCreateDTO;
import com.ticketai.entity.TicketDO;
import com.ticketai.entity.TicketStatusLogDO;
import com.ticketai.mapper.TicketMapper;
import com.ticketai.mapper.TicketStatusLogMapper;
import com.ticketai.query.TicketQuery;
import com.ticketai.security.LoginUser;
import com.ticketai.security.UserContextHolder;
import com.ticketai.service.TicketService;
import com.ticketai.state.StateMachine;
import com.ticketai.state.TicketEvent;
import com.ticketai.state.TicketStatus;
import com.ticketai.state.Transition;
import com.ticketai.vo.TicketStatusLogVO;
import com.ticketai.vo.TicketVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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
        log.info("工单创建: id={}, ticketNo={}", ticket.getId(), ticket.getTicketNo());
        return ticket;
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

        // 3. 乐观锁更新状态（@Version 自动携带 version 条件，冲突抛异常）
        ticket.setStatus(transition.to().getCode());
        ticket.setUpdateBy(currentUsername());
        ticket.setUpdateTime(LocalDateTime.now());
        int rows = ticketMapper.updateById(ticket);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFY);
        }

        // 4. 状态日志
        writeStatusLog(ticketId, from, transition.to(), event, operatorId, operatorType, null);

        // 5. 事件后置动作（M1 范围：时间戳结算；SLA/分派/负载在 M2-M4 扩展）
        applyPostActions(ticket, transition.to(), event);
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

    /** 事件后置动作（DEV_DOC §5.1.5，M1 实现时间戳结算部分） */
    private void applyPostActions(TicketDO ticket, TicketStatus to, TicketEvent event) {
        LocalDateTime now = LocalDateTime.now();
        if (event == TicketEvent.REPLY && ticket.getFirstRespondedAt() == null) {
            ticket.setFirstRespondedAt(now);
            ticketMapper.updateById(ticket);
        } else if (event == TicketEvent.RESOLVE) {
            ticket.setResolvedAt(now);
            ticketMapper.updateById(ticket);
        } else if (event == TicketEvent.CLOSE || event == TicketEvent.CANCEL) {
            ticket.setClosedAt(now);
            ticketMapper.updateById(ticket);
        } else if (event == TicketEvent.REOPEN) {
            ticket.setResolvedAt(null);
            ticket.setClosedAt(null);
            ticketMapper.updateById(ticket);
        }
    }

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
