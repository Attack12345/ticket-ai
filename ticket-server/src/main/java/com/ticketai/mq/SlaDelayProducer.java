package com.ticketai.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * SLA 延迟消息生产者（DEV_DOC §5.2.2）。
 * 优先 RocketMQ 5.x 定时消息（任意延迟）；消息发送失败不影响主流程（补偿扫描兜底）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaDelayProducer {

    public static final String TOPIC = "ticket-sla-check";

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 投递延迟检查消息。
     *
     * @param deliverAtMs 绝对时间戳（毫秒），到期投递
     */
    public void send(Long ticketId, Long slaId, String checkType, long deliverAtMs) {
        try {
            SlaMessage body = new SlaMessage(ticketId, slaId, checkType, null);
            Message message = new Message(TOPIC, checkType,
                    objectMapper.writeValueAsBytes(body));
            // RocketMQ 5.x 定时消息：任意延迟（broker 时间轮）
            message.setDeliverTimeMs(deliverAtMs);
            DefaultMQProducer producer = rocketMQTemplate.getProducer();
            producer.send(message);
            log.info("SLA 延迟消息投递: ticketId={}, type={}, deliverAt={}", ticketId, checkType, deliverAtMs);
        } catch (JsonProcessingException e) {
            log.error("SLA 消息序列化失败: ticketId={}", ticketId, e);
        } catch (Exception e) {
            // 发送失败不抛：由补偿扫描兜底
            log.error("SLA 延迟消息发送失败（将由补偿扫描兜底）: ticketId={}, type={}", ticketId, checkType, e);
        }
    }
}
