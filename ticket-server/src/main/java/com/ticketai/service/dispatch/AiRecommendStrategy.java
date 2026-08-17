package com.ticketai.service.dispatch;

import com.ticketai.entity.TicketDO;
import com.ticketai.service.AiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI 推荐策略（DEV_DOC §5.3.1）：调 AiService.aiDispatch。
 * LLM 不可用或返回 null → 工厂自动降级其他策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRecommendStrategy implements DispatchStrategy {

    private final AiService aiService;

    @Override
    public String type() {
        return "AI_RECOMMEND";
    }

    @Override
    public Long dispatch(TicketDO ticket) {
        return aiService.aiDispatch(ticket.getId());
    }
}
