package com.ticketai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * SLA 策略创建/更新入参
 */
@Data
public class SlaPolicyDTO {

    @NotBlank(message = "策略名不能为空")
    @Size(max = 50, message = "策略名最长 50 字")
    private String name;

    /** 适用优先级：1-紧急 2-高 3-中 4-低 */
    @NotNull(message = "优先级不能为空")
    @Min(value = 1, message = "优先级范围 1-4")
    @Max(value = 4, message = "优先级范围 1-4")
    private Integer priority;

    /** 首次响应时限（分钟） */
    @NotNull(message = "首次响应时限不能为空")
    @Min(value = 1, message = "响应时限最小 1 分钟")
    private Integer firstResponseMinutes;

    /** 解决时限（分钟） */
    @NotNull(message = "解决时限不能为空")
    @Min(value = 1, message = "解决时限最小 1 分钟")
    private Integer resolveMinutes;

    /** 超时是否自动升级 */
    private Integer autoEscalate = 1;

    /** 升级动作 JSON，如 {"notifyGroupId":1} */
    @Size(max = 500, message = "升级动作最长 500 字")
    private String escalateAction;

    private Integer status = 1;

    private String remark;
}
