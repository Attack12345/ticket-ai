package com.ticketai.service.impl;

import com.ticketai.ai.LlmClient;
import com.ticketai.ai.LlmException;
import com.ticketai.common.exception.BusinessException;
import com.ticketai.common.exception.ErrorCode;
import com.ticketai.entity.TicketDO;
import com.ticketai.es.KnowledgeIndexService;
import com.ticketai.es.TicketIndexService;
import com.ticketai.mapper.TicketMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * AI 服务测试（DEV_DOC M6 DoD：mock LLM 正常/异常路径）。
 */
@ExtendWith(MockitoExtension.class)
class AiServiceImplTest {

    @Mock
    private LlmClient llmClient;
    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private KnowledgeIndexService knowledgeIndexService;
    @Mock
    private TicketIndexService ticketIndexService;

    private AiServiceImpl newService() {
        return new AiServiceImpl(llmClient, ticketMapper, knowledgeIndexService,
                ticketIndexService, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private TicketDO ticket() {
        TicketDO ticket = new TicketDO();
        ticket.setId(10L);
        ticket.setTitle("退款问题");
        ticket.setDescription("客户要求退款");
        return ticket;
    }

    @Test
    @DisplayName("回复建议成功：生成草稿并带引用")
    void suggestReplySuccess() {
        when(ticketMapper.selectById(10L)).thenReturn(ticket());
        when(llmClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        Map<String, Object> hitSource = Map.of("ticket_no", "T20260817000001", "content", "历史处理：同意退款");
        var hit = new co.elastic.clients.elasticsearch.core.search.Hit.Builder<Map<String, Object>>()
                .index("ticket_index").id("1").source(hitSource).score(0.9).build();
        when(ticketIndexService.similarTickets(any(), anyInt())).thenReturn(List.of(hit));
        when(knowledgeIndexService.search(anyString(), any(), anyInt())).thenReturn(List.of());
        when(llmClient.chatJson(any(), anyString(), any())).thenReturn(
                "{\"reply\": \"您好，退款申请已受理，1-3 个工作日原路退回。\"}");

        var vo = newService().suggestReply(10L);

        assertEquals("您好，退款申请已受理，1-3 个工作日原路退回。", vo.getReply());
        assertEquals(1, vo.getKbRefs().size());
    }

    @Test
    @DisplayName("回复建议降级：LLM 不可用 → AI_UNAVAILABLE")
    void suggestReplyUnavailable() {
        when(ticketMapper.selectById(10L)).thenReturn(ticket());
        when(llmClient.embed(anyString()))
                .thenThrow(new LlmException("NO_KEY", LlmException.NO_KEY));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> newService().suggestReply(10L));
        assertEquals(ErrorCode.AI_UNAVAILABLE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("AI 分派降级：LLM 不可用返回 null（工厂自动降级）")
    void aiDispatchFallback() {
        when(ticketMapper.selectById(10L)).thenReturn(ticket());
        when(llmClient.chatJson(any(), anyString(), any()))
                .thenThrow(new LlmException("TIMEOUT", LlmException.TIMEOUT));

        assertNull(newService().aiDispatch(10L));
    }

    @Test
    @DisplayName("AI 分派成功：解析 agentId")
    void aiDispatchSuccess() {
        when(ticketMapper.selectById(10L)).thenReturn(ticket());
        when(llmClient.chatJson(any(), anyString(), any()))
                .thenReturn("{\"agentId\": 5, \"reason\": \"售后技能匹配\"}");

        assertEquals(5L, newService().aiDispatch(10L));
    }
}
