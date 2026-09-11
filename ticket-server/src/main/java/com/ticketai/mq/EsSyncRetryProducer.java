package com.ticketai.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * ES 同步重试生产者（DEV_DOC §4.2.6）：ES 写入失败进重试队列。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsSyncRetryProducer {

    public static final String TOPIC = "es-sync-retry";

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    /** 分段索引写入重试（type=indexSegment） */
    public void sendRetry(Long segmentId, Long kbId, String title, String category, String content) {
        send(Map.of(
                "type", "indexSegment",
                "segmentId", segmentId, "kbId", kbId,
                "title", title == null ? "" : title,
                "category", category == null ? "" : category,
                "content", content == null ? "" : content));
    }

    /** 分段 ES 文档删除重试（P1-1：编辑重建时清理旧文档失败） */
    public void sendDeleteSegment(Long segmentId) {
        send(Map.of("type", "delete", "segmentId", segmentId));
    }

    /** 相似工单索引写入重试（P1-4：复用 es-sync-retry 队列） */
    public void sendTicketIndexRetry(Long ticketId) {
        send(Map.of("type", "ticket", "ticketId", ticketId));
    }

    private void send(Map<String, Object> payload) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(payload);
            rocketMQTemplate.syncSend(TOPIC, new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("ES 重试消息发送失败（补偿对账兜底）: payload={}", payload, e);
        }
    }
}
