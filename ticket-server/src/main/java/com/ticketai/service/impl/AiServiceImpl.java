package com.ticketai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketai.ai.LlmClient;
import com.ticketai.ai.LlmException;
import com.ticketai.ai.Message;
import com.ticketai.common.exception.BusinessException;
import com.ticketai.common.exception.ErrorCode;
import com.ticketai.entity.TicketDO;
import com.ticketai.es.KnowledgeIndexService;
import com.ticketai.es.TicketIndexService;
import com.ticketai.mapper.TicketMapper;
import com.ticketai.service.AiService;
import com.ticketai.vo.AiSuggestVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 服务（DEV_DOC §5.5.2）：RAG 回复建议 + AI 分派建议。
 * 全部为"建议"：LLM 不可用 → 回复建议抛明确错误、分派建议返回 null（策略工厂降级）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    private final LlmClient llmClient;
    private final TicketMapper ticketMapper;
    private final KnowledgeIndexService knowledgeIndexService;
    private final TicketIndexService ticketIndexService;
    private final ObjectMapper objectMapper;

    @Override
    public AiSuggestVO suggestReply(Long ticketId) {
        TicketDO ticket = requireTicket(ticketId);
        try {
            // 1. 工单内容向量化
            float[] vector = llmClient.embed(ticket.getTitle() + "\n" + ticket.getDescription());
            // 2. 召回：相似已解决工单 top3 + 知识库 top3
            List<String> refs = new ArrayList<>();
            StringBuilder context = new StringBuilder();
            for (var hit : ticketIndexService.similarTickets(vector, 3)) {
                Map<String, Object> source = hit.source();
                context.append("【历史工单】").append(source.get("content")).append("\n");
                refs.add("相似工单 " + source.get("ticket_no"));
            }
            for (var hit : knowledgeIndexService.search("", vector, 3)) {
                Map<String, Object> source = hit.source();
                context.append("【知识库】").append(source.get("content")).append("\n");
                refs.add("知识库: " + source.get("title"));
            }
            // 3. 生成回复草稿
            String json = llmClient.chatJson(ticketId, "SUGGEST", List.of(
                    Message.system("你是资深客服。参考给出的历史工单和知识库内容，为客户问题撰写回复草稿。"
                            + "输出 JSON：{\"reply\": \"回复内容\", \"kbRefs\": [引用标题...]}。"
                            + "回复不超过 500 字，语气专业礼貌，不得编造不存在的政策。"
                            + (context.isEmpty() ? "没有参考资料时，基于常识礼貌回复并建议人工跟进。" : "")),
                    Message.user("客户问题: " + ticket.getTitle() + "\n" + ticket.getDescription()
                            + "\n\n参考资料:\n" + context)));
            JsonNode result = objectMapper.readTree(json);
            AiSuggestVO vo = new AiSuggestVO();
            String reply = result.path("reply").asText();
            vo.setReply(reply.length() > 500 ? reply.substring(0, 500) : reply);
            vo.setKbRefs(refs.isEmpty() ? List.of() : refs);
            log.info("AI 回复建议生成: ticketId={}, 引用 {} 条", ticketId, refs.size());
            return vo;
        } catch (LlmException e) {
            log.warn("AI 回复建议不可用: ticketId={}, reason={}", ticketId, e.getReason());
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "AI 服务暂不可用: " + e.getReason());
        } catch (Exception e) {
            log.error("AI 回复建议异常: ticketId={}", ticketId, e);
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "AI 服务暂不可用");
        }
    }

    @Override
    public Long aiDispatch(Long ticketId) {
        TicketDO ticket = requireTicket(ticketId);
        try {
            String json = llmClient.chatJson(ticketId, "DISPATCH", List.of(
                    Message.system("你是客服排班助手。根据工单信息推荐合适的坐席。"
                            + "输出 JSON：{\"agentId\": 数字或null, \"reason\": \"简短理由\"}。"
                            + "信息不足时 agentId 返回 null。"),
                    Message.user("工单: " + ticket.getTitle() + "\n" + ticket.getDescription()
                            + "\n分类: " + (ticket.getCategory() == null ? "未分类" : ticket.getCategory()))));
            JsonNode result = objectMapper.readTree(json);
            JsonNode agentId = result.path("agentId");
            if (agentId.isNull() || agentId.isMissingNode()) {
                return null;
            }
            return agentId.asLong();
        } catch (LlmException e) {
            log.debug("AI 分派不可用，返回 null（工厂降级）: reason={}", e.getReason());
            return null;
        } catch (Exception e) {
            log.warn("AI 分派异常，返回 null（工厂降级）: ticketId={}", ticketId);
            return null;
        }
    }

    private TicketDO requireTicket(Long id) {
        TicketDO ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工单不存在: id=" + id);
        }
        return ticket;
    }
}
