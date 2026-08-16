package com.ticketai.vo;

import com.ticketai.state.TicketEvent;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单详情 VO
 */
@Data
public class TicketVO {

    private Long id;
    private String ticketNo;
    private String title;
    private String description;
    private String category;
    private Integer priority;
    private Integer status;
    /** 状态文本（中文） */
    private String statusText;
    private Long groupId;
    private Long agentId;
    private String assignStrategy;
    private String customerName;
    private String customerContact;
    private Long slaPolicyId;
    private LocalDateTime firstResponseDeadline;
    private LocalDateTime resolveDeadline;
    private LocalDateTime firstRespondedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime closedAt;
    private String aiCategory;
    private Integer aiPriority;
    private BigDecimal aiScore;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 当前状态允许触发的事件（前端据此控制按钮可用性） */
    private List<TicketEvent> allowedEvents;
}
