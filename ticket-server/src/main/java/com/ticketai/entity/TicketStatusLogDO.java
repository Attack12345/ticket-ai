package com.ticketai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单状态流转日志（ticket_status_log）。全量事件流，禁止删除。
 */
@Data
@TableName("ticket_status_log")
public class TicketStatusLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    /** 原状态码 */
    private Integer fromStatus;

    /** 新状态码 */
    private Integer toStatus;

    /** 触发事件编码，见 TicketEvent */
    private String event;

    /** 操作人ID（系统事件为 NULL） */
    private Long operatorId;

    /** USER / SYSTEM */
    private String operatorType;

    private String remark;

    private LocalDateTime createTime;
}
