package com.ticketai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SLA 策略（sla_policy）。每个优先级一条策略。
 */
@Data
@TableName("sla_policy")
public class SlaPolicyDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 适用优先级：1-紧急 2-高 3-中 4-低（唯一） */
    private Integer priority;

    /** 首次响应时限（分钟） */
    private Integer firstResponseMinutes;

    /** 解决时限（分钟） */
    private Integer resolveMinutes;

    /** 超时是否自动升级：0-否 1-是 */
    private Integer autoEscalate;

    /** 升级动作 JSON，如 {"notifyGroupId":1} */
    private String escalateAction;

    private Integer status;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    private String remark;
}
