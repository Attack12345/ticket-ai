package com.ticketai.controller;

import com.ticketai.common.PageResult;
import com.ticketai.common.Result;
import com.ticketai.dto.AcceptCategoryDTO;
import com.ticketai.dto.TicketAssignDTO;
import com.ticketai.dto.TicketCreateDTO;
import com.ticketai.dto.TicketReplyDTO;
import com.ticketai.query.TicketQuery;
import com.ticketai.service.TicketService;
import com.ticketai.state.TicketEvent;
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

    @PostMapping("/{id}/claim")
    @PreAuthorize("hasAuthority('ticket:claim')")
    @Operation(summary = "抢单（Redisson 锁 + 乐观锁双保险）")
    public Result<TicketVO> claim(@PathVariable Long id) {
        return Result.ok(ticketService.getDetail(ticketService.claim(id).getId()));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAuthority('ticket:assign')")
    @Operation(summary = "手动分派")
    public Result<TicketVO> assign(@PathVariable Long id, @RequestBody @Valid TicketAssignDTO dto) {
        ticketService.transition(id, TicketEvent.MANUAL_ASSIGN, null, "USER",
                dto.getAgentId(), null, "MANUAL");
        return Result.ok(ticketService.getDetail(id));
    }

    @PostMapping("/{id}/reply")
    @PreAuthorize("hasAuthority('ticket:reply')")
    @Operation(summary = "回复客户")
    public Result<TicketVO> reply(@PathVariable Long id, @RequestBody @Valid TicketReplyDTO dto) {
        ticketService.reply(id, dto);
        return Result.ok(ticketService.getDetail(id));
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAuthority('ticket:resolve')")
    @Operation(summary = "标记解决")
    public Result<TicketVO> resolve(@PathVariable Long id) {
        ticketService.transition(id, TicketEvent.RESOLVE, null, "USER");
        return Result.ok(ticketService.getDetail(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('ticket:close')")
    @Operation(summary = "关闭工单")
    public Result<TicketVO> close(@PathVariable Long id) {
        ticketService.transition(id, TicketEvent.CLOSE, null, "USER");
        return Result.ok(ticketService.getDetail(id));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAuthority('ticket:resolve')")
    @Operation(summary = "重开工单")
    public Result<TicketVO> reopen(@PathVariable Long id) {
        ticketService.transition(id, TicketEvent.REOPEN, null, "USER");
        return Result.ok(ticketService.getDetail(id));
    }

    @PostMapping("/{id}/escalate")
    @PreAuthorize("hasAuthority('ticket:escalate')")
    @Operation(summary = "人工升级")
    public Result<TicketVO> escalate(@PathVariable Long id) {
        ticketService.transition(id, TicketEvent.ESCALATE, null, "USER");
        return Result.ok(ticketService.getDetail(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('ticket:close')")
    @Operation(summary = "取消工单")
    public Result<TicketVO> cancel(@PathVariable Long id) {
        ticketService.transition(id, TicketEvent.CANCEL, null, "USER");
        return Result.ok(ticketService.getDetail(id));
    }

    @PostMapping("/{id}/accept-category")
    @PreAuthorize("hasAuthority('ticket:view')")
    @Operation(summary = "采纳 AI 分类")
    public Result<Void> acceptCategory(@PathVariable Long id, @RequestBody @Valid AcceptCategoryDTO dto) {
        ticketService.acceptCategory(id, dto);
        return Result.ok();
    }
}
