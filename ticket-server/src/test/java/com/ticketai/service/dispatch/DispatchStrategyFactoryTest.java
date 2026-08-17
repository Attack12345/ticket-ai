package com.ticketai.service.dispatch;

import com.ticketai.entity.DispatchStrategyDO;
import com.ticketai.entity.TicketDO;
import com.ticketai.mapper.DispatchStrategyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 分派工厂测试（DEV_DOC M4 DoD：AI_RECOMMEND 未启用时工厂正确跳过；全失败保持待分派）。
 */
@ExtendWith(MockitoExtension.class)
class DispatchStrategyFactoryTest {

    @Mock
    private DispatchStrategyMapper dispatchStrategyMapper;

    private DispatchStrategy nullStrategy(String type) {
        return new DispatchStrategy() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Long dispatch(TicketDO ticket) {
                return null;
            }
        };
    }

    private TicketDO ticket() {
        TicketDO ticket = new TicketDO();
        ticket.setId(1L);
        ticket.setCategory("售后");
        return ticket;
    }

    @Test
    @DisplayName("AI_RECOMMEND 未启用：工厂按权重只尝试启用策略，跳过禁用策略")
    void skipsDisabledStrategy() {
        DispatchStrategyDO skillMatch = config("SKILL_MATCH", 90, 1);
        DispatchStrategyDO aiRecommend = config("AI_RECOMMEND", 60, 0);
        when(dispatchStrategyMapper.selectList(any())).thenReturn(List.of(skillMatch, aiRecommend));

        DispatchStrategy skillStrategy = new DispatchStrategy() {
            @Override
            public String type() {
                return "SKILL_MATCH";
            }

            @Override
            public Long dispatch(TicketDO t) {
                return 100L;
            }
        };
        DispatchStrategyFactory factory = new DispatchStrategyFactory(dispatchStrategyMapper,
                List.of(skillStrategy, nullStrategy("AI_RECOMMEND")));

        DispatchStrategyFactory.DispatchResult result = factory.dispatch(ticket());

        assertEquals(100L, result.agentId());
        assertEquals("SKILL_MATCH", result.strategy());
    }

    @Test
    @DisplayName("全策略返回 null：工厂返回 null（工单保持待分派，由 CLAIM 兜底）")
    void allStrategiesFail() {
        when(dispatchStrategyMapper.selectList(any())).thenReturn(List.of(
                config("ROUND_ROBIN", 100, 1),
                config("LEAST_LOADED", 80, 1)));

        DispatchStrategyFactory factory = new DispatchStrategyFactory(dispatchStrategyMapper,
                List.of(nullStrategy("ROUND_ROBIN"), nullStrategy("LEAST_LOADED")));

        assertNull(factory.dispatch(ticket()));
    }

    @Test
    @DisplayName("无启用策略：返回 null")
    void noEnabledStrategy() {
        when(dispatchStrategyMapper.selectList(any())).thenReturn(List.of(config("ROUND_ROBIN", 100, 0)));

        DispatchStrategyFactory factory = new DispatchStrategyFactory(dispatchStrategyMapper,
                List.of(nullStrategy("ROUND_ROBIN")));

        assertNull(factory.dispatch(ticket()));
    }

    private DispatchStrategyDO config(String type, int weight, int enabled) {
        DispatchStrategyDO config = new DispatchStrategyDO();
        config.setStrategyType(type);
        config.setWeight(weight);
        config.setEnabled(enabled);
        return config;
    }
}
