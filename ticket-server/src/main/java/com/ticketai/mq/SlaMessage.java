package com.ticketai.mq;

import java.time.LocalDateTime;

/**
 * SLA 延迟检查消息（DEV_DOC §5.2.2）。
 */
public record SlaMessage(Long ticketId, Long slaId, String checkType, LocalDateTime deadline) {

    /** 检查类型：首次响应 / 解决 */
    public static final String TYPE_FIRST_RESPONSE = "FIRST_RESPONSE";
    public static final String TYPE_RESOLVE = "RESOLVE";
}
