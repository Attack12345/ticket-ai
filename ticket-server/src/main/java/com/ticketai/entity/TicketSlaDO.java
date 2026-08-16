package com.ticketai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单 SLA 计时实例（ticket_sla）。每张工单一份。
 */
@Data
@TableName("ticket_sla")
public class TicketSlaDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    private Long slaPolicyId;

    private LocalDateTime firstResponseDeadline;

    private LocalDateTime resolveDeadline;

    /** 实际首次响应时间 */
    private LocalDateTime firstRespondedAt;

    /** 实际解决时间 */
    private LocalDateTime resolvedAt;

    /** 响应状态：0-未到期 1-按时 2-超时 */
    private Integer firstResponseStatus;

    /** 解决状态：0-未到期 1-按时 2-超时 */
    private Integer resolveStatus;

    /** 是否已触发升级：0-否 1-是 */
    private Integer escalationTriggered;

    private LocalDateTime escalatedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
