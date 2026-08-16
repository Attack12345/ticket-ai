package com.ticketai.vo;

import lombok.Data;

/**
 * 技能组 VO
 */
@Data
public class SkillGroupVO {

    private Long id;
    private String name;
    private String description;
    /** 状态：0-禁用 1-启用 */
    private Integer status;
    /** 组内坐席数 */
    private Long agentCount;
}
