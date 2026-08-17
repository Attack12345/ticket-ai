package com.ticketai.controller;

import com.ticketai.common.Result;
import com.ticketai.service.DashboardService;
import com.ticketai.vo.DashboardStatsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@Tag(name = "统计看板")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('dashboard:view')")
    @Operation(summary = "看板统计")
    public Result<DashboardStatsVO> stats() {
        return Result.ok(dashboardService.stats());
    }
}
