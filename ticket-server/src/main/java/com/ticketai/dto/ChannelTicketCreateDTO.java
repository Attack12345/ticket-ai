package com.ticketai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 客户渠道创建工单入参（公开接口，DEV_DOC §6.4）
 */
@Data
public class ChannelTicketCreateDTO {

    @NotBlank(message = "messageNo 不能为空")
    @Size(max = 64, message = "messageNo 最长 64 字")
    private String messageNo;

    @Size(max = 50, message = "客户名最长 50 字")
    private String customerName;

    @Size(max = 100, message = "联系方式最长 100 字")
    private String customerContact;

    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题最长 200 字")
    private String title;

    @Size(max = 5000, message = "内容最长 5000 字")
    private String content;
}
