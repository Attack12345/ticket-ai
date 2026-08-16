package com.ticketai.state;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工单事件枚举（DEV_DOC §5.1.2）
 */
@Getter
@AllArgsConstructor
public enum TicketEvent {

    SUBMIT("提交"),
    AUTO_ASSIGN("自动分派"),
    MANUAL_ASSIGN("手动分派"),
    CLAIM("领取"),
    REPLY("回复"),
    CUSTOMER_REPLY("客户回复"),
    RESOLVE("解决"),
    CLOSE("关闭"),
    REOPEN("重开"),
    ESCALATE("人工升级"),
    TIMEOUT_ESCALATE("SLA超时升级"),
    CANCEL("取消");

    private final String desc;
}
