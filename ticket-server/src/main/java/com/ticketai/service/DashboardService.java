package com.ticketai.service;

import com.ticketai.vo.DashboardStatsVO;

public interface DashboardService {

    /** 看板统计（DEV_DOC §6.5）：各状态分布、SLA 按时率、平均响应时长、今日新增/解决 */
    DashboardStatsVO stats();
}
