package com.ticketai.controller;

import com.ticketai.common.Result;
import com.ticketai.dto.SlaPolicyDTO;
import com.ticketai.service.SlaPolicyService;
import com.ticketai.vo.SlaPolicyVO;
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
@RequestMapping("/api/v1/sla-policies")
@RequiredArgsConstructor
@Tag(name = "SLA 策略")
public class SlaPolicyController {

    private final SlaPolicyService slaPolicyService;

    @GetMapping
    @PreAuthorize("hasAuthority('ticket:view')")
    @Operation(summary = "SLA 策略列表")
    public Result<List<SlaPolicyVO>> list() {
        return Result.ok(slaPolicyService.list());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('sla:manage')")
    @Operation(summary = "创建 SLA 策略")
    public Result<Map<String, Object>> create(@RequestBody @Valid SlaPolicyDTO dto) {
        return Result.ok(Map.of("id", slaPolicyService.create(dto)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('sla:manage')")
    @Operation(summary = "更新 SLA 策略")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid SlaPolicyDTO dto) {
        slaPolicyService.update(id, dto);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('sla:manage')")
    @Operation(summary = "删除 SLA 策略")
    public Result<Void> delete(@PathVariable Long id) {
        slaPolicyService.delete(id);
        return Result.ok();
    }
}
