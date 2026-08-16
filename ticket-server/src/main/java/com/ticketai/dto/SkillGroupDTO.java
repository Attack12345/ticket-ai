package com.ticketai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 技能组创建/更新入参
 */
@Data
public class SkillGroupDTO {

    @NotBlank(message = "技能组名不能为空")
    @Size(max = 50, message = "技能组名最长 50 字")
    private String name;

    @Size(max = 200, message = "描述最长 200 字")
    private String description;

    /** 状态：0-禁用 1-启用 */
    private Integer status = 1;
}
