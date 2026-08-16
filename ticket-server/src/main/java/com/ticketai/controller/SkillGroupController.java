package com.ticketai.controller;

import com.ticketai.common.Result;
import com.ticketai.dto.SkillGroupAgentsDTO;
import com.ticketai.dto.SkillGroupDTO;
import com.ticketai.service.SkillGroupService;
import com.ticketai.vo.SkillGroupVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/skill-groups")
@RequiredArgsConstructor
@Tag(name = "技能组")
public class SkillGroupController {

    private final SkillGroupService skillGroupService;

    @GetMapping
    @PreAuthorize("hasAuthority('ticket:view')")
    @Operation(summary = "技能组列表")
    public Result<List<SkillGroupVO>> list() {
        return Result.ok(skillGroupService.list());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('agent:manage')")
    @Operation(summary = "创建技能组")
    public Result<Map<String, Object>> create(@RequestBody @Valid SkillGroupDTO dto) {
        return Result.ok(Map.of("id", skillGroupService.create(dto)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('agent:manage')")
    @Operation(summary = "更新技能组")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid SkillGroupDTO dto) {
        skillGroupService.update(id, dto);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('agent:manage')")
    @Operation(summary = "删除技能组")
    public Result<Void> delete(@PathVariable Long id) {
        skillGroupService.delete(id);
        return Result.ok();
    }

    @PutMapping("/{id}/agents")
    @PreAuthorize("hasAuthority('agent:manage')")
    @Operation(summary = "批量设置组内坐席")
    public Result<Void> setAgents(@PathVariable Long id, @RequestBody @Valid SkillGroupAgentsDTO dto) {
        skillGroupService.setAgents(id, dto);
        return Result.ok();
    }
}
