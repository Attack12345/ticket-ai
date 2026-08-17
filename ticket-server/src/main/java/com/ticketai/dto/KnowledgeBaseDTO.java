package com.ticketai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 知识库文章入参
 */
@Data
public class KnowledgeBaseDTO {

    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题最长 200 字")
    private String title;

    @Size(max = 50, message = "分类最长 50 字")
    private String category;

    @NotBlank(message = "内容不能为空")
    @Size(max = 100_000, message = "内容最长 10 万字")
    private String content;

    /** 状态：0-下架 1-上架 */
    private Integer status = 1;
}
