package com.ticketai.service.dispatch;

import com.ticketai.entity.TicketDO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI 推荐策略占位（M6 接入 LlmClient 后实现）。
 * 当前一律返回 null（工厂自动降级到其他策略），保持分派链路可用。
 */
@Slf4j
@Component
public class AiRecommendStrategy implements DispatchStrategy {

    @Override
    public String type() {
        return "AI_RECOMMEND";
    }

    @Override
    public Long dispatch(TicketDO ticket) {
        log.debug("AI 推荐策略未实现（M6 接入），降级: ticketId={}", ticket.getId());
        return null;
    }
}
