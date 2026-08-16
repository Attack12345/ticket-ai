package com.ticketai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TicketCreateDTO {

    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题最长 200 字")
    private String title;

    @Size(max = 5000, message = "描述最长 5000 字")
    private String description;

    /** 分类（售后/售前/投诉/咨询/其他），可为空（坐席后续确认） */
    @Size(max = 50, message = "分类最长 50 字")
    private String category;

    /** 优先级：1-紧急 2-高 3-中 4-低 */
    @Min(value = 1, message = "优先级范围 1-4")
    @Max(value = 4, message = "优先级范围 1-4")
    private Integer priority = 3;

    @Size(max = 50, message = "客户名最长 50 字")
    private String customerName;

    @Size(max = 100, message = "联系方式最长 100 字")
    private String customerContact;

    @NotNull(message = "渠道ID不能为空")
    private Long channelId;
}
