package com.ticketai.service.dispatch;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketai.entity.AgentDO;
import com.ticketai.entity.TicketDO;
import com.ticketai.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 轮询策略：Redis INCR 计数 % 在线坐席数（DEV_DOC §5.3.1）。
 */
@Component
@RequiredArgsConstructor
public class RoundRobinStrategy implements DispatchStrategy {

    private static final String RR_KEY = "dispatch:rr";

    private final AgentMapper agentMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public String type() {
        return "ROUND_ROBIN";
    }

    @Override
    public Long dispatch(TicketDO ticket) {
        List<AgentDO> online = agentMapper.selectList(new LambdaQueryWrapper<AgentDO>()
                .eq(AgentDO::getStatus, 1)
                .orderByAsc(AgentDO::getId));
        if (online.isEmpty()) {
            return null;
        }
        Long counter = stringRedisTemplate.opsForValue().increment(RR_KEY);
        if (counter == null) {
            return online.get(0).getId();
        }
        return online.get((int) (counter % online.size())).getId();
    }
}
