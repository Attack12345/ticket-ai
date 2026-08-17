package com.ticketai.event;

/**
 * 工单解决事件（RESOLVED 时发布，异步写相似工单索引）。
 */
public record TicketResolvedEvent(Long ticketId) {
}
