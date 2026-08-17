package com.ticketai.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 手动分派入参
 */
@Data
public class TicketAssignDTO {

    @NotNull(message = "坐席ID不能为空")
    private Long agentId;
}
