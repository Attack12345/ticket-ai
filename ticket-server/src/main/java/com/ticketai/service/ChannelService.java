package com.ticketai.service;

import com.ticketai.dto.ChannelTicketCreateDTO;

import java.util.Map;

public interface ChannelService {

    /**
     * WEB_API 渠道创建工单（公开接口，DEV_DOC §6.4）。
     * 幂等：messageNo 重复返回已建工单。
     */
    Map<String, Object> webApiCreateTicket(ChannelTicketCreateDTO dto);

    /**
     * 校验渠道接入凭证（Authorization: Bearer <appKey>）并做调用方限流（P0-2）。
     * 无效/超限抛 BusinessException；渠道接口调用前必须执行。
     */
    void assertWebApiRequest(String authorization);
}
