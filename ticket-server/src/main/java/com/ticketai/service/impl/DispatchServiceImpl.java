package com.ticketai.service.impl;

import com.ticketai.entity.AgentDO;
import com.ticketai.entity.SkillGroupAgentDO;
import com.ticketai.entity.TicketDO;
import com.ticketai.event.TicketCreatedEvent;
import com.ticketai.mapper.AgentMapper;
import com.ticketai.mapper.SkillGroupAgentMapper;
import com.ticketai.service.DispatchService;
import com.ticketai.service.TicketService;
import com.ticketai.service.dispatch.DispatchStrategyFactory;
import com.ticketai.state.TicketEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 自动分派服务（DEV_DOC §5.3.1）：
 * 监听 TicketCreatedEvent（事务提交后发布）异步执行，不阻塞工单创建主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchServiceImpl implements DispatchService {

    private final DispatchStrategyFactory strategyFactory;
    private final TicketService ticketService;
    private final AgentMapper agentMapper;
    private final SkillGroupAgentMapper skillGroupAgentMapper;

    @Override
    public void dispatchAsync(TicketDO ticket) {
        try {
            DispatchStrategyFactory.DispatchResult result = strategyFactory.dispatch(ticket);
            if (result == null) {
                log.info("自动分派未命中，工单保持待分派: ticketId={}", ticket.getId());
                return;
            }
            Long groupId = findGroupId(result.agentId());
            // SYSTEM 事件 + 后置参数（agent/group/strategy 并入同一条 UPDATE）
            ticketService.transition(ticket.getId(), TicketEvent.AUTO_ASSIGN, null, "SYSTEM",
                    result.agentId(), groupId, result.strategy());
        } catch (Exception e) {
            // 分派失败不影响主流程（工单保持待分派，可抢单）
            log.error("自动分派异常，工单保持待分派: ticketId={}", ticket.getId(), e);
        }
    }

    /** 事务提交后异步触发（数据可见性保证） */
    @EventListener
    @Async("dispatchExecutor")
    public void onTicketCreated(TicketCreatedEvent event) {
        dispatchAsync(event.ticket());
    }

    private Long findGroupId(Long agentId) {
        SkillGroupAgentDO relation = skillGroupAgentMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SkillGroupAgentDO>()
                        .eq(SkillGroupAgentDO::getAgentId, agentId)
                        .last("LIMIT 1"));
        return relation == null ? null : relation.getGroupId();
    }
}
