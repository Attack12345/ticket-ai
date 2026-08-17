package com.ticketai.service.dispatch;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketai.entity.AgentDO;
import com.ticketai.entity.TicketDO;
import com.ticketai.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 负载最低策略：在线坐席按 current_load 升序，取最小（同负载取 id 最小）。
 */
@Component
@RequiredArgsConstructor
public class LeastLoadedStrategy implements DispatchStrategy {

    private final AgentMapper agentMapper;

    @Override
    public String type() {
        return "LEAST_LOADED";
    }

    @Override
    public Long dispatch(TicketDO ticket) {
        List<AgentDO> online = agentMapper.selectList(new LambdaQueryWrapper<AgentDO>()
                .eq(AgentDO::getStatus, 1)
                .orderByAsc(AgentDO::getCurrentLoad)
                .orderByAsc(AgentDO::getId));
        return online.isEmpty() ? null : online.get(0).getId();
    }
}
