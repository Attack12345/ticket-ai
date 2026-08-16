package com.ticketai.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 坐席更新入参（技能标签）
 */
@Data
public class AgentUpdateDTO {

    @NotEmpty(message = "技能标签不能为空")
    @Size(max = 10, message = "技能标签最多 10 个")
    private List<@Size(max = 20, message = "单个技能标签最长 20 字") String> skillTags;
}
