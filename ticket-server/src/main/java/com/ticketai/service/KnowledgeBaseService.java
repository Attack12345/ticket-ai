package com.ticketai.service;

import com.ticketai.common.PageResult;
import com.ticketai.dto.KbSearchDTO;
import com.ticketai.dto.KnowledgeBaseDTO;
import com.ticketai.vo.KbSearchHitVO;
import com.ticketai.vo.KnowledgeBaseVO;

import java.util.List;

public interface KnowledgeBaseService {

    PageResult<KnowledgeBaseVO> pageList(int page, int size, String keyword);

    KnowledgeBaseVO getDetail(Long id);

    Long create(KnowledgeBaseDTO dto);

    void update(Long id, KnowledgeBaseDTO dto);

    /** 删除 = 逻辑删除 + ES 同步删除（异步） */
    void delete(Long id);

    /** 检索（全文 + 可选向量加权，embedding 不可用自动降级全文） */
    List<KbSearchHitVO> search(KbSearchDTO dto);
}
