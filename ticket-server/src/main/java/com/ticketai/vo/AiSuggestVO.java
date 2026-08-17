package com.ticketai.vo;

import lombok.Data;

import java.util.List;

/**
 * AI 回复建议 VO
 */
@Data
public class AiSuggestVO {

    /** 回复草稿 */
    private String reply;

    /** 引用的知识库/相似工单条目 */
    private List<String> kbRefs;
}
