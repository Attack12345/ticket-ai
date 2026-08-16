package com.ticketai.state;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工单状态枚举（DEV_DOC §5.1.1）
 */
@Getter
@AllArgsConstructor
public enum TicketStatus {

    NEW(1, "新建"),
    PENDING_ASSIGN(2, "待分派"),
    PROCESSING(3, "处理中"),
    WAITING_CUSTOMER(4, "等待客户"),
    RESOLVED(5, "已解决"),
    CLOSED(6, "已关闭"),
    ESCALATED(7, "已升级"),
    CANCELLED(8, "已取消");

    private final int code;
    private final String desc;

    public static TicketStatus byCode(int code) {
        for (TicketStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知工单状态 code: " + code);
    }
}
