package com.ticketai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志（audit_log）
 */
@Data
@TableName("audit_log")
public class AuditLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String username;

    /** 动作，如 TICKET_ASSIGN / SLA_ESCALATE */
    private String action;

    private String targetType;

    private Long targetId;

    /** 详情 JSON */
    private String detailJson;

    private String ip;

    private LocalDateTime createTime;
}
