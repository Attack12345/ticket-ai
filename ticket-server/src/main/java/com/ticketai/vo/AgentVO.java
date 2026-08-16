package com.ticketai.vo;

import lombok.Data;

import java.util.List;

/**
 * 坐席 VO
 */
@Data
public class AgentVO {

    private Long id;
    private Long userId;
    private String name;
    /** 在线状态：0-离线 1-在线 */
    private Integer status;
    /** 当前负载 */
    private Integer currentLoad;
    /** 技能标签 */
    private List<String> skillTags;
    /** 所属技能组 ID 列表 */
    private List<Long> groupIds;
}
