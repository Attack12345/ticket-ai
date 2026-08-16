package com.ticketai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 坐席（agent）
 */
@Data
@TableName("agent")
public class AgentDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联系统用户 ID */
    private Long userId;

    private String name;

    /** 在线状态：0-离线 1-在线 */
    private Integer status;

    /** 当前负载（处理中+等待客户工单数） */
    private Integer currentLoad;

    /** 技能标签 JSON 数组，如 ["售后","投诉"] */
    private String skillTags;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    private String remark;
}
