package com.ticketai.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketai.es.KnowledgeIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ES 同步重试消费者：重写失败的分段索引（消息体 String，见 §0.5.2 坑 5）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = EsSyncRetryProducer.TOPIC, consumerGroup = "es-sync-retry-consumer")
public class EsSyncRetryConsumer implements RocketMQListener<String> {

    private final ObjectMapper objectMapper;
    private final KnowledgeIndexService knowledgeIndexService;

    @Override
    @SuppressWarnings("unchecked")
    public void onMessage(String body) {
        try {
            Map<String, Object> payload = objectMapper.readValue(body, Map.class);
            knowledgeIndexService.indexSegment(
                    Long.valueOf(String.valueOf(payload.get("segmentId"))),
                    Long.valueOf(String.valueOf(payload.get("kbId"))),
                    (String) payload.get("title"),
                    (String) payload.get("category"),
                    (String) payload.get("content"));
            log.info("ES 重试写入成功: segmentId={}", payload.get("segmentId"));
        } catch (Exception e) {
            log.error("ES 重试仍失败（等待补偿对账）: {}", body, e);
        }
    }
}
