package com.ticketai.controller;

import com.ticketai.common.Result;
import com.ticketai.dto.ChannelTicketCreateDTO;
import com.ticketai.service.ChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 渠道接入（公开接口，免认证，见 SecurityConfig 白名单）
 */
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
@Tag(name = "渠道接入")
public class ChannelController {

    private final ChannelService channelService;

    @PostMapping("/web-api/tickets")
    @Operation(summary = "客户渠道创建工单（幂等：messageNo 重复返回已建工单）")
    public Result<Map<String, Object>> webApiCreateTicket(@RequestBody @Valid ChannelTicketCreateDTO dto) {
        return Result.ok(channelService.webApiCreateTicket(dto));
    }
}
