package com.ticketai.service;

import com.ticketai.mq.SlaMessage;

public interface SlaService {

    /**
     * 工单创建后启动 SLA 计时（DEV_DOC §5.2.1）：
     * 按优先级匹配策略 → 建 ticket_sla → 投递响应/解决两条延迟消息。
     */
    void createTicketSla(Long ticketId, Integer priority);

    /** 延迟消息到点结算（幂等：已升级/已结算忽略） */
    void handleDelayCheck(SlaMessage message);

    /** 补偿扫描：兜底超时未升级的工单（消息丢失场景） */
    void compensate();
}
