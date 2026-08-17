package com.ticketai.event;

import com.ticketai.entity.TicketDO;

/**
 * 工单创建完成事件（事务提交后发布，异步触发自动分派）。
 */
public record TicketCreatedEvent(TicketDO ticket) {
}
