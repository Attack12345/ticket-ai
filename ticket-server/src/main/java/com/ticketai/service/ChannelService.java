package com.ticketai.service;

import com.ticketai.dto.ChannelTicketCreateDTO;

import java.util.Map;

public interface ChannelService {

    /**
     * WEB_API 渠道创建工单（公开接口，DEV_DOC §6.4）。
     * 幂等：messageNo 重复返回已建工单。
     */
    Map<String, Object> webApiCreateTicket(ChannelTicketCreateDTO dto);
}
