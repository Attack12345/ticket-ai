package com.ticketai.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 技能组批量设置坐席入参
 */
@Data
public class SkillGroupAgentsDTO {

    @NotEmpty(message = "坐席列表不能为空")
    private List<Long> agentIds;
}
