package com.ticketai.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketai.entity.TicketDO;
import com.ticketai.es.KnowledgeIndexService;
import com.ticketai.es.TicketIndexService;
import com.ticketai.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ES 同步重试消费者（消息体 String，见 §0.5.2 坑 5）：分段写入/删除、相似工单索引的失败重试。
 * 异常策略：瞬时故障（ES 网络不可达，IOException）抛出交 RocketMQ 重投（重试耗尽进死信）；
 * 数据问题（消息体畸形、缺必填字段、工单不存在）确认移除并落 ERROR 日志，避免无限重投毒化消费。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = EsSyncRetryProducer.TOPIC, consumerGroup = "es-sync-retry-consumer")
public class EsSyncRetryConsumer implements RocketMQListener<String> {

    private final ObjectMapper objectMapper;
    private final KnowledgeIndexService knowledgeIndexService;
    private final TicketMapper ticketMapper;
    private final TicketIndexService ticketIndexService;

    @Override
    @SuppressWarnings("unchecked")
    public void onMessage(String body) {
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            // 数据问题（消息体畸形）：确认并记录，重投也不会成功
            log.error("ES 重试消息反序列化失败（数据问题，放弃重试）: body={}", body, e);
            return;
        }
        String type = payload.get("type") == null ? "indexSegment" : String.valueOf(payload.get("type"));
        try {
            switch (type) {
                case "delete" -> {
                    Object segmentId = payload.get("segmentId");
                    if (segmentId == null) {
                        log.error("ES 删除重试消息缺少 segmentId（数据问题，放弃重试）: {}", body);
                        return;
                    }
                    knowledgeIndexService.deleteSegment(Long.valueOf(String.valueOf(segmentId)));
                    log.info("ES 重试删除成功: segmentId={}", segmentId);
                }
                case "ticket" -> {
                    Object ticketId = payload.get("ticketId");
                    if (ticketId == null) {
                        log.error("相似工单索引重试消息缺少 ticketId（数据问题，放弃重试）: {}", body);
                        return;
                    }
                    TicketDO ticket = ticketMapper.selectById(Long.valueOf(String.valueOf(ticketId)));
                    if (ticket == null) {
                        log.error("相似工单索引重试时工单不存在（数据问题，放弃重试）: ticketId={}", ticketId);
                        return;
                    }
                    ticketIndexService.indexTicket(ticket);
                    log.info("ES 重试写入相似工单索引成功: ticketId={}", ticketId);
                }
                case "indexSegment" -> {
                    Object segmentId = payload.get("segmentId");
                    Object kbId = payload.get("kbId");
                    if (segmentId == null || kbId == null) {
                        log.error("ES 重试消息缺少必填字段 segmentId/kbId（数据问题，放弃重试）: {}", body);
                        return;
                    }
                    knowledgeIndexService.indexSegment(
                            Long.valueOf(String.valueOf(segmentId)),
                            Long.valueOf(String.valueOf(kbId)),
                            (String) payload.get("title"),
                            (String) payload.get("category"),
                            (String) payload.get("content"));
                    log.info("ES 重试写入成功: segmentId={}", segmentId);
                }
                default -> log.error("未知 ES 重试类型（数据问题，放弃重试）: type={}, body={}", type, body);
            }
        } catch (java.io.IOException e) {
            // 瞬时故障（ES 网络不可用等）：抛出交 RocketMQ 重投（消费幂等，重试耗尽进死信可见）
            log.error("ES 重试执行失败（瞬时故障），抛出让 RocketMQ 重投: type={}, body={}", type, body, e);
            throw new RuntimeException(e);
        } catch (RuntimeException e) {
            // 数据问题（字段类型错误等）：确认移除并记录，避免无限重投
            log.error("ES 重试数据问题不可重试（已确认）: type={}, body={}", type, body, e);
        }
    }
}
