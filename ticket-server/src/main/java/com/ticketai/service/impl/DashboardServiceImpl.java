package com.ticketai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ticketai.entity.TicketDO;
import com.ticketai.entity.TicketSlaDO;
import com.ticketai.mapper.TicketMapper;
import com.ticketai.mapper.TicketSlaMapper;
import com.ticketai.service.DashboardService;
import com.ticketai.vo.DashboardStatsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 看板统计服务（DEV_DOC §6.5）。指标全部下沉 SQL 层聚合，杜绝全表入内存（P1-7）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final TicketMapper ticketMapper;
    private final TicketSlaMapper ticketSlaMapper;

    @Override
    public DashboardStatsVO stats() {
        DashboardStatsVO vo = new DashboardStatsVO();

        // 1. 各状态工单数：GROUP BY 聚合，代替全表拉取后流式分组
        vo.setTotalByStatus(countByStatus());

        // 2. SLA 按时率：已结算（响应/解决状态为 1/2）中按时占比，条件 count 代替遍历
        long settled = ticketSlaMapper.selectCount(new LambdaQueryWrapper<TicketSlaDO>()
                .and(w -> w.eq(TicketSlaDO::getFirstResponseStatus, 1)
                        .or().eq(TicketSlaDO::getFirstResponseStatus, 2)
                        .or().eq(TicketSlaDO::getResolveStatus, 1)
                        .or().eq(TicketSlaDO::getResolveStatus, 2)));
        long onTime = ticketSlaMapper.selectCount(new LambdaQueryWrapper<TicketSlaDO>()
                .and(w -> w.eq(TicketSlaDO::getFirstResponseStatus, 1)
                        .or().eq(TicketSlaDO::getResolveStatus, 1)));
        vo.setSlaOnTimeRate(settled == 0 ? null : (double) onTime / settled);

        // 3. 平均首次响应时长（分钟）：AVG(TIMESTAMPDIFF) 由 DB 聚合
        vo.setAvgFirstResponseMinutes(avgFirstResponseMinutes());

        // 4. 今日新增/解决：条件 count（NULL 时间自然被排除）
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        vo.setTodayNew(ticketMapper.selectCount(new LambdaQueryWrapper<TicketDO>()
                .ge(TicketDO::getCreateTime, dayStart)));
        vo.setTodayResolved(ticketMapper.selectCount(new LambdaQueryWrapper<TicketDO>()
                .ge(TicketDO::getResolvedAt, dayStart)));
        return vo;
    }

    /** 按状态分组计数（无工单返回空 Map，key=status code, value=数量） */
    private Map<Integer, Long> countByStatus() {
        return ticketMapper.selectMaps(new QueryWrapper<TicketDO>()
                        .select("status", "COUNT(*) AS cnt")
                        .groupBy("status"))
                .stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row.get("status")).intValue(),
                        row -> ((Number) row.get("cnt")).longValue()));
    }

    /** 首次响应均长（分钟，保留 1 位小数）；无首次响应记录返回 null */
    private Double avgFirstResponseMinutes() {
        List<Map<String, Object>> rows = ticketMapper.selectMaps(new QueryWrapper<TicketDO>()
                .select("AVG(TIMESTAMPDIFF(MINUTE, create_time, first_responded_at)) AS avgMin")
                .isNotNull("create_time")
                .isNotNull("first_responded_at"));
        if (rows.isEmpty() || rows.get(0).get("avgMin") == null) {
            return null;
        }
        double avgMin = ((Number) rows.get(0).get("avgMin")).doubleValue();
        return Math.round(avgMin * 10) / 10.0;
    }
}
