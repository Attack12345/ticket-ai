package com.ticketai.vo;

import lombok.Data;

/**
 * 知识库检索命中 VO
 */
@Data
public class KbSearchHitVO {

    private Long segmentId;
    private Long kbId;
    private String title;
    private String category;
    /** 命中片段内容 */
    private String content;
    /** 相关度分数 */
    private Double score;
}
