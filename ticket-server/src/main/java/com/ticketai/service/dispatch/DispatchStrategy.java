package com.ticketai.service.dispatch;

import com.ticketai.entity.TicketDO;

/**
 * 分派策略接口（DEV_DOC §5.3.1）。
 * 实现返回建议的坐席 agentId，找不到返回 null（工厂继续尝试下一个策略）。
 */
public interface DispatchStrategy {

    /** 策略编码（与 dispatch_strategy.strategy_type 一致） */
    String type();

    /** 为工单推荐坐席，返回 null 表示本策略无法分派 */
    Long dispatch(TicketDO ticket);
}
