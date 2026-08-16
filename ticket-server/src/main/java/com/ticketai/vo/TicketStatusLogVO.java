package com.ticketai.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单状态流转日志 VO（时间线条目）
 */
@Data
public class TicketStatusLogVO {

    private Long id;
    private Integer fromStatus;
    private String fromStatusText;
    private Integer toStatus;
    private String toStatusText;
    private String event;
    private Long operatorId;
    private String operatorType;
    private String remark;
    private LocalDateTime createTime;
}
