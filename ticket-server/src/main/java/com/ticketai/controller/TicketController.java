package com.ticketai.controller;

import com.ticketai.common.PageResult;
import com.ticketai.common.Result;
import com.ticketai.dto.TicketCreateDTO;
import com.ticketai.query.TicketQuery;
import com.ticketai.service.TicketService;
import com.ticketai.vo.TicketStatusLogVO;
import com.ticketai.vo.TicketVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
@Tag(name = "工单")
public class TicketController {

    private final TicketService ticketService;

    @GetMapping
    @PreAuthorize("hasAuthority('ticket:view')")
    @Operation(summary = "工单分页列表")
    public Result<PageResult<TicketVO>> list(TicketQuery query) {
        return Result.ok(ticketService.pageList(query));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ticket:view')")
    @Operation(summary = "工单详情")
    public Result<TicketVO> detail(@PathVariable Long id) {
        return Result.ok(ticketService.getDetail(id));
    }

    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAuthority('ticket:view')")
    @Operation(summary = "工单时间线（状态流转日志）")
    public Result<List<TicketStatusLogVO>> timeline(@PathVariable Long id) {
        return Result.ok(ticketService.timeline(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ticket:view')")
    @Operation(summary = "创建工单")
    public Result<Map<String, Object>> create(@RequestBody @Valid TicketCreateDTO dto) {
        var ticket = ticketService.create(dto);
        return Result.ok(Map.of("id", ticket.getId(), "ticketNo", ticket.getTicketNo()));
    }
}
