package com.ticketai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 技能组-坐席关联（skill_group_agent）
 */
@Data
@TableName("skill_group_agent")
public class SkillGroupAgentDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long groupId;

    private Long agentId;
}
