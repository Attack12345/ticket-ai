package com.ticketai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工单回复入参
 */
@Data
public class TicketReplyDTO {

    @NotBlank(message = "回复内容不能为空")
    @Size(max = 2000, message = "回复内容最长 2000 字")
    private String content;

    /** 可见性：ALL-客户可见 INTERNAL-仅内部 */
    private String visibility = "ALL";
}
