package com.ticketai.service.dispatch;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketai.entity.DispatchStrategyDO;
import com.ticketai.entity.TicketDO;
import com.ticketai.mapper.DispatchStrategyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分派策略工厂（DEV_DOC §5.3.1）：
 * 读 dispatch_strategy 表 enabled 策略，按 weight 降序逐个尝试，第一个返回非 null 的生效。
 * 全失败返回 null（工单保持 PENDING_ASSIGN，由 CLAIM 抢单兜底）。
 */
@Slf4j
@Component
public class DispatchStrategyFactory {

    private final DispatchStrategyMapper dispatchStrategyMapper;
    private final Map<String, DispatchStrategy> strategyMap;

    /**
     * 构造：注入全部策略实现，按 type()（与表 strategy_type 一致）构建映射。
     * 注意不能依赖 Spring 按 bean 名注入 Map（key 是小驼峰类名，与编码不匹配）。
     */
    public DispatchStrategyFactory(DispatchStrategyMapper dispatchStrategyMapper,
                                   List<DispatchStrategy> strategies) {
        this.dispatchStrategyMapper = dispatchStrategyMapper;
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(DispatchStrategy::type, Function.identity()));
    }

    /**
     * 按配置执行分派，返回 {agentId, strategy}；全策略失败返回 null。
     */
    public DispatchResult dispatch(TicketDO ticket) {
        List<DispatchStrategyDO> configs = dispatchStrategyMapper.selectList(
                        new LambdaQueryWrapper<DispatchStrategyDO>()
                                .eq(DispatchStrategyDO::getEnabled, 1))
                .stream()
                .sorted(Comparator.comparingInt(DispatchStrategyDO::getWeight).reversed())
                .toList();
        if (configs.isEmpty()) {
            log.warn("无启用分派策略，工单保持待分派: ticketId={}", ticket.getId());
            return null;
        }
        for (DispatchStrategyDO config : configs) {
            DispatchStrategy strategy = strategyMap.get(config.getStrategyType());
            if (strategy == null) {
                log.warn("未注册的分派策略: {}", config.getStrategyType());
                continue;
            }
            Long agentId = strategy.dispatch(ticket);
            if (agentId != null) {
                log.info("分派成功: ticketId={}, strategy={}, agentId={}",
                        ticket.getId(), strategy.type(), agentId);
                return new DispatchResult(agentId, strategy.type());
            }
        }
        log.info("全部分派策略未命中，工单保持待分派: ticketId={}", ticket.getId());
        return null;
    }

    /** 分派结果：agentId + 实际生效策略编码 */
    public record DispatchResult(Long agentId, String strategy) {
    }
}
