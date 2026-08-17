package com.ticketai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketai.entity.TicketDO;
import com.ticketai.event.TicketCreatedEvent;
import com.ticketai.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 自动分类 + 优先级建议（DEV_DOC §5.5.2 场景1）。
 * 工单创建后异步执行；SETNX 防重复调用（LLM 有成本，幂等红线）。
 * 分类失败（LLM 不可用）→ 静默降级（ai_category 为空，坐席手工分类），工单流程不受影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketClassifier {

    private static final String LOCK_PREFIX = "ai:classify:";
    private static final long LOCK_TTL_SECONDS = 600;

    private final LlmClient llmClient;
    private final TicketMapper ticketMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @EventListener
    @Async("dispatchExecutor")
    public void onTicketCreated(TicketCreatedEvent event) {
        TicketDO ticket = event.ticket();
        // 幂等防重：SETNX 成功才执行（LLM 调用有成本）
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_PREFIX + ticket.getId(), "1",
                        java.time.Duration.ofSeconds(LOCK_TTL_SECONDS));
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("分类任务已执行过，跳过: ticketId={}", ticket.getId());
            return;
        }
        try {
            classify(ticket);
        } finally {
            stringRedisTemplate.delete(LOCK_PREFIX + ticket.getId());
        }
    }

    private void classify(TicketDO ticket) {
        try {
            String json = llmClient.chatJson(ticket.getId(), "CLASSIFY", List.of(
                    Message.system("你是客服工单分类器。根据工单标题和描述，输出 JSON："
                            + "{\"category\": \"售后|售前|投诉|咨询|其他\", \"priority\": 1|2|3|4, \"confidence\": 0.0-1.0}。"
                            + "category 必须从给定集合中选择，priority 1 最高 4 最低。"),
                    Message.user("标题: " + ticket.getTitle() + "\n描述: " + ticket.getDescription())));
            JsonNode result = objectMapper.readTree(json);
            String category = result.path("category").asText();
            int priority = result.path("priority").asInt(3);
            double confidence = result.path("confidence").asDouble(0);
            if (category.isBlank()) {
                log.warn("分类结果缺 category，忽略: ticketId={}", ticket.getId());
                return;
            }
            TicketDO update = new TicketDO();
            update.setId(ticket.getId());
            update.setAiCategory(category);
            update.setAiPriority(priority);
            update.setAiScore(java.math.BigDecimal.valueOf(confidence));
            ticketMapper.updateById(update);
            log.info("工单自动分类完成: ticketId={}, category={}, priority={}, confidence={}",
                    ticket.getId(), category, priority, confidence);
        } catch (LlmException e) {
            log.warn("工单分类降级（LLM 不可用）: ticketId={}, reason={}", ticket.getId(), e.getReason());
        } catch (Exception e) {
            log.error("工单分类异常（降级）: ticketId={}", ticket.getId(), e);
        }
    }
}
