package com.ticketai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketai.common.exception.BusinessException;
import com.ticketai.common.exception.ErrorCode;
import com.ticketai.dto.ChannelTicketCreateDTO;
import com.ticketai.dto.TicketCreateDTO;
import com.ticketai.entity.ChannelDO;
import com.ticketai.entity.ChannelMessageDO;
import com.ticketai.entity.TicketDO;
import com.ticketai.mapper.ChannelMapper;
import com.ticketai.mapper.ChannelMessageMapper;
import com.ticketai.mapper.TicketMapper;
import com.ticketai.service.ChannelService;
import com.ticketai.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 渠道服务（DEV_DOC §4.2.3 / §6.4）。
 * 幂等创建：message_no 唯一键 + 并发冲突时查回已建工单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelServiceImpl implements ChannelService {

    private static final String WEB_API_CODE = "WEB_API";

    private final ChannelMapper channelMapper;
    private final ChannelMessageMapper channelMessageMapper;
    private final TicketService ticketService;
    private final TicketMapper ticketMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> webApiCreateTicket(ChannelTicketCreateDTO dto) {
        // 1. 渠道校验
        ChannelDO channel = channelMapper.selectOne(new LambdaQueryWrapper<ChannelDO>()
                .eq(ChannelDO::getCode, WEB_API_CODE)
                .eq(ChannelDO::getStatus, 1));
        if (channel == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "WEB_API 渠道未启用");
        }

        // 2. 幂等命中：messageNo 已存在 → 返回已建工单
        ChannelMessageDO existed = channelMessageMapper.selectOne(new LambdaQueryWrapper<ChannelMessageDO>()
                .eq(ChannelMessageDO::getMessageNo, dto.getMessageNo()));
        if (existed != null && existed.getTicketId() != null) {
            TicketDO ticket = ticketMapper.selectById(existed.getTicketId());
            if (ticket != null) {
                log.info("渠道消息幂等命中: messageNo={}, ticketId={}", dto.getMessageNo(), ticket.getId());
                return buildResult(ticket);
            }
        }

        // 3. 落渠道消息（唯一键兜底并发冲突）
        ChannelMessageDO message = new ChannelMessageDO();
        message.setChannelId(channel.getId());
        message.setMessageNo(dto.getMessageNo());
        message.setDirection(1);
        message.setCustomerName(dto.getCustomerName());
        message.setCustomerContact(dto.getCustomerContact());
        message.setTitle(dto.getTitle());
        message.setContent(dto.getContent());
        message.setCreateTime(LocalDateTime.now());
        try {
            channelMessageMapper.insert(message);
        } catch (DuplicateKeyException e) {
            // 并发下重复 messageNo：查回已建工单
            ChannelMessageDO dup = channelMessageMapper.selectOne(new LambdaQueryWrapper<ChannelMessageDO>()
                    .eq(ChannelMessageDO::getMessageNo, dto.getMessageNo()));
            if (dup != null && dup.getTicketId() != null) {
                TicketDO dupTicket = ticketMapper.selectById(dup.getTicketId());
                if (dupTicket != null) {
                    return buildResult(dupTicket);
                }
            }
            throw new BusinessException(ErrorCode.DUPLICATE_MESSAGE, "渠道消息重复: " + dto.getMessageNo());
        }

        // 4. 创建工单并回填关联
        TicketCreateDTO ticketDto = new TicketCreateDTO();
        ticketDto.setTitle(dto.getTitle());
        ticketDto.setDescription(dto.getContent());
        ticketDto.setCustomerName(dto.getCustomerName());
        ticketDto.setCustomerContact(dto.getCustomerContact());
        ticketDto.setChannelId(channel.getId());
        ticketDto.setPriority(3);
        TicketDO ticket = ticketService.create(ticketDto);

        ticket.setChannelMessageId(message.getId());
        ticketMapper.updateById(ticket);
        message.setTicketId(ticket.getId());
        channelMessageMapper.updateById(message);

        log.info("渠道创建工单: messageNo={}, ticketNo={}", dto.getMessageNo(), ticket.getTicketNo());
        return buildResult(ticket);
    }

    private Map<String, Object> buildResult(TicketDO ticket) {
        return Map.of("id", ticket.getId(), "ticketNo", ticket.getTicketNo());
    }
}
