package com.ticketai.vo;

import lombok.Data;

import java.util.Map;

/**
 * 看板统计 VO（DEV_DOC §6.5）
 */
@Data
public class DashboardStatsVO {

    /** 各状态工单数（key=status code） */
    private Map<Integer, Long> totalByStatus;

    /** SLA 按时率（0-1） */
    private Double slaOnTimeRate;

    /** 平均首次响应时长（分钟） */
    private Double avgFirstResponseMinutes;

    /** 今日新增 */
    private Long todayNew;

    /** 今日解决 */
    private Long todayResolved;
}
