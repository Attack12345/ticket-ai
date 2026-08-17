package com.ticketai.service;

import com.ticketai.entity.TicketDO;
import com.ticketai.event.TicketCreatedEvent;

public interface DispatchService {

    /**
     * 异步自动分派（SUBMIT 后触发）：工厂按权重尝试策略，
     * 成功 → transition(AUTO_ASSIGN) 并写入 agent/group/strategy；失败 → 保持待分派。
     */
    void dispatchAsync(TicketDO ticket);

    /** 工单创建事件监听（@Async + @EventListener 需在接口声明，JDK 动态代理要求） */
    void onTicketCreated(TicketCreatedEvent event);
}
