package com.ticketai.ai;

import com.ticketai.entity.TicketDO;
import com.ticketai.event.TicketCreatedEvent;
import com.ticketai.mapper.TicketMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动分类测试（DEV_DOC M6 DoD：LLM 正常写回、异常降级、SETNX 防重）。
 */
@ExtendWith(MockitoExtension.class)
class TicketClassifierTest {

    @Mock
    private LlmClient llmClient;
    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    @DisplayName("分类成功：写回 ai_category/priority/score")
    void classifySuccess() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(llmClient.chatJson(any(), eq("CLASSIFY"), any())).thenReturn(
                "{\"category\": \"售后\", \"priority\": 2, \"confidence\": 0.93}");
        when(ticketMapper.updateById(any(TicketDO.class))).thenReturn(1);

        TicketClassifier classifier = new TicketClassifier(llmClient, ticketMapper, stringRedisTemplate,
                new com.fasterxml.jackson.databind.ObjectMapper());
        TicketDO ticket = new TicketDO();
        ticket.setId(10L);
        ticket.setTitle("耳机坏了");
        ticket.setDescription("请求维修");
        classifier.onTicketCreated(new TicketCreatedEvent(ticket));

        verify(ticketMapper).updateById(any(TicketDO.class));
        verify(stringRedisTemplate).delete(anyString());
    }

    @Test
    @DisplayName("LLM 异常：静默降级（不抛异常、不更新工单）")
    void classifyFallbackOnLlmError() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(llmClient.chatJson(any(), eq("CLASSIFY"), any()))
                .thenThrow(new LlmException("NO_KEY", LlmException.NO_KEY));

        TicketClassifier classifier = new TicketClassifier(llmClient, ticketMapper, stringRedisTemplate,
                new com.fasterxml.jackson.databind.ObjectMapper());
        TicketDO ticket = new TicketDO();
        ticket.setId(10L);
        ticket.setTitle("耳机坏了");
        classifier.onTicketCreated(new TicketCreatedEvent(ticket));

        verify(ticketMapper, never()).updateById(any(TicketDO.class));
    }

    @Test
    @DisplayName("SETNX 防重：重复事件直接跳过，不重复调 LLM")
    void duplicateEventSkipped() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        TicketClassifier classifier = new TicketClassifier(llmClient, ticketMapper, stringRedisTemplate,
                new com.fasterxml.jackson.databind.ObjectMapper());
        TicketDO ticket = new TicketDO();
        ticket.setId(10L);
        classifier.onTicketCreated(new TicketCreatedEvent(ticket));

        verify(llmClient, never()).chatJson(any(), any(), any());
    }
}
