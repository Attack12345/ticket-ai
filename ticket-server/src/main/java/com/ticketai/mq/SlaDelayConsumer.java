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
 * 幂等：已升级/已结算的消息直接忽略。
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
        try {
            SlaMessage message = objectMapper.readValue(body, SlaMessage.class);
            log.info("SLA 延迟消息到达: ticketId={}, slaId={}, type={}",
                    message.ticketId(), message.slaId(), message.checkType());
            slaService.handleDelayCheck(message);
        } catch (Exception e) {
            // 解析或处理失败：记录日志，由补偿扫描兜底
            log.error("SLA 延迟消息处理失败（将由补偿扫描兜底）", e);
        }
    }
}
