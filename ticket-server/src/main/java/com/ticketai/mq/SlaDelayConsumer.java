package com.ticketai.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketai.service.SlaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * SLA 延迟检查消费者（DEV_DOC §5.2.1）。
 * 注意：监听器泛型必须用 String（starter 默认按 String 转换消息体），byte[] 会 ClassCastException。
 * 幂等：已升级/已结算的消息直接忽略，重复消费安全。
 * 异常策略：消息体畸形属数据问题（确认 + ERROR 日志）；处理中的瞬时故障抛出交 RocketMQ 重投。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = SlaDelayProducer.TOPIC, consumerGroup = "ticket-sla-consumer")
public class SlaDelayConsumer implements RocketMQListener<String> {

    private final ObjectMapper objectMapper;
    private final SlaService slaService;

    @Override
    public void onMessage(String body) {
        final SlaMessage message;
        try {
            message = objectMapper.readValue(body, SlaMessage.class);
        } catch (Exception e) {
            // 数据问题（消息体畸形）：确认并记录，重投也不会成功，避免无限重投
            log.error("SLA 消息反序列化失败（数据问题，已确认）: body={}", body, e);
            return;
        }
        log.info("SLA 延迟消息到达: ticketId={}, slaId={}, type={}",
                message.ticketId(), message.slaId(), message.checkType());
        try {
            slaService.handleDelayCheck(message);
        } catch (Exception e) {
            // 瞬时故障（DB 抖动等）：抛出交 RocketMQ 重投（handleDelayCheck 幂等，重复处理安全）
            log.error("SLA 延迟消息处理失败，抛出让 RocketMQ 重投: ticketId={}, slaId={}, type={}",
                    message.ticketId(), message.slaId(), message.checkType(), e);
            throw new RuntimeException(e);
        }
    }
}
