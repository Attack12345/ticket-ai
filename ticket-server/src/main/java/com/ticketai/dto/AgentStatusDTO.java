package com.ticketai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 坐席状态更新入参（上下线）
 */
@Data
public class AgentStatusDTO {

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态：0-离线 1-在线")
    @Max(value = 1, message = "状态：0-离线 1-在线")
    private Integer status;
}
