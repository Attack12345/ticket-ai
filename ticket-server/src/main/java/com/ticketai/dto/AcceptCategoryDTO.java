package com.ticketai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 采纳 AI 分类入参
 */
@Data
public class AcceptCategoryDTO {

    @NotBlank(message = "分类不能为空")
    @Size(max = 50, message = "分类最长 50 字")
    private String category;

    @Min(value = 1, message = "优先级范围 1-4")
    @Max(value = 4, message = "优先级范围 1-4")
    private Integer priority;
}
