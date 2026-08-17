package com.ticketai.service;

import com.ticketai.vo.AiSuggestVO;

public interface AiService {

    /** RAG 回复建议（DEV_DOC §5.5.2 场景2），LLM 不可用抛 AI_UNAVAILABLE 错误 */
    AiSuggestVO suggestReply(Long ticketId);

    /** AI 分派建议（DEV_DOC §5.5.2 场景3），返回 agentId；LLM 不可用返回 null */
    Long aiDispatch(Long ticketId);
}
