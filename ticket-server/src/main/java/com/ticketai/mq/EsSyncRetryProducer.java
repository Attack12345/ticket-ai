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

    public void sendRetry(Long segmentId, Long kbId, String title, String category, String content) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "segmentId", segmentId, "kbId", kbId,
                    "title", title == null ? "" : title,
                    "category", category == null ? "" : category,
                    "content", content == null ? "" : content));
            rocketMQTemplate.syncSend(TOPIC, new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("ES 重试消息发送失败（补偿对账兜底）: segmentId={}", segmentId, e);
        }
    }
}
