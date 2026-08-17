package com.ticketai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 知识库检索入参（DEV_DOC §5.4.3）
 */
@Data
public class KbSearchDTO {

    @NotBlank(message = "关键词不能为空")
    @Size(max = 200, message = "关键词最长 200 字")
    private String keyword;

    /** 语义检索开关：false 时仅全文检索（embedding 不可用自动降级） */
    private Boolean semantic = true;

    private Integer topN = 5;
}
