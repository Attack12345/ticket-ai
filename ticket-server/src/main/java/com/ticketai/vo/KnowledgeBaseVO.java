package com.ticketai.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文章 VO
 */
@Data
public class KnowledgeBaseVO {

    private Long id;
    private String title;
    private String category;
    private String content;
    /** 状态：0-下架 1-上架 */
    private Integer status;
    private Integer viewCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
