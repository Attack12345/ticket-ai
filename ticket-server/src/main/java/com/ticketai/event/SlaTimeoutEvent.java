package com.ticketai.event;

/**
 * SLA 超时升级事件。由 SlaService 发布，TicketService 监听后走状态机 TIMEOUT_ESCALATE。
 * 事件解耦避免 SlaService 与 TicketService 循环依赖。
 */
public record SlaTimeoutEvent(Long ticketId) {
}
