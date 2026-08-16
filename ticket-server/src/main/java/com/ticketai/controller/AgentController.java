package com.ticketai.controller;

import com.ticketai.common.Result;
import com.ticketai.dto.AgentStatusDTO;
import com.ticketai.dto.AgentUpdateDTO;
import com.ticketai.service.AgentService;
import com.ticketai.vo.AgentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
@Tag(name = "坐席")
public class AgentController {

    private final AgentService agentService;

    @GetMapping
    @PreAuthorize("hasAuthority('ticket:view')")
    @Operation(summary = "坐席列表（可按技能组/在线状态过滤）")
    public Result<List<AgentVO>> list(@RequestParam(required = false) Long groupId,
                                      @RequestParam(required = false) Integer status) {
        return Result.ok(agentService.list(groupId, status));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('agent:manage')")
    @Operation(summary = "坐席上下线")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody @Valid AgentStatusDTO dto) {
        agentService.updateStatus(id, dto);
        return Result.ok();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('agent:manage')")
    @Operation(summary = "更新坐席技能标签")
    public Result<Void> updateSkillTags(@PathVariable Long id, @RequestBody @Valid AgentUpdateDTO dto) {
        agentService.updateSkillTags(id, dto);
        return Result.ok();
    }
}
