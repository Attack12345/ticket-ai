package com.ticketai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 看板统计服务（DEV_DOC §6.5）。
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

        // 1. 各状态工单数
        List<TicketDO> allTickets = ticketMapper.selectList(null);
        Map<Integer, Long> byStatus = allTickets.stream()
                .collect(Collectors.groupingBy(TicketDO::getStatus, Collectors.counting()));
        vo.setTotalByStatus(byStatus);

        // 2. SLA 按时率：已结算（响应状态 1/2 或解决状态 1/2）中按时占比
        List<TicketSlaDO> settled = ticketSlaMapper.selectList(new LambdaQueryWrapper<TicketSlaDO>()
                .and(w -> w.eq(TicketSlaDO::getFirstResponseStatus, 1)
                        .or().eq(TicketSlaDO::getFirstResponseStatus, 2)
                        .or().eq(TicketSlaDO::getResolveStatus, 1)
                        .or().eq(TicketSlaDO::getResolveStatus, 2)));
        long onTime = settled.stream()
                .filter(s -> s.getFirstResponseStatus() == 1 || s.getResolveStatus() == 1)
                .count();
        vo.setSlaOnTimeRate(settled.isEmpty() ? null : (double) onTime / settled.size());

        // 3. 平均首次响应时长（分钟）：有首次响应时间的工单
        List<TicketDO> responded = allTickets.stream()
                .filter(t -> t.getFirstRespondedAt() != null && t.getCreateTime() != null)
                .toList();
        if (responded.isEmpty()) {
            vo.setAvgFirstResponseMinutes(null);
        } else {
            double avgMinutes = responded.stream()
                    .mapToLong(t -> ChronoUnit.MINUTES.between(t.getCreateTime(), t.getFirstRespondedAt()))
                    .average().orElse(0);
            vo.setAvgFirstResponseMinutes(Math.round(avgMinutes * 10) / 10.0);
        }

        // 4. 今日新增/解决
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        vo.setTodayNew(allTickets.stream()
                .filter(t -> t.getCreateTime() != null && !t.getCreateTime().isBefore(dayStart))
                .count());
        vo.setTodayResolved(allTickets.stream()
                .filter(t -> t.getResolvedAt() != null && !t.getResolvedAt().isBefore(dayStart))
                .count());
        return vo;
    }
}
