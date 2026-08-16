package com.ticketai.state;

/**
 * 状态转移定义。
 * requiredPermission 为 "SYSTEM" 表示仅系统内部可触发（如自动分派、SLA 超时升级）。
 */
public record Transition(TicketStatus from, TicketEvent event, TicketStatus to, String requiredPermission) {

    public boolean isSystemOnly() {
        return "SYSTEM".equals(requiredPermission);
    }
}
