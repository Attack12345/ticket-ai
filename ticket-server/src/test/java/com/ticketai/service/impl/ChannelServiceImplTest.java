package com.ticketai.service.impl;

import com.ticketai.dto.ChannelTicketCreateDTO;
import com.ticketai.common.exception.BusinessException;
import com.ticketai.entity.ChannelDO;
import com.ticketai.entity.ChannelMessageDO;
import com.ticketai.entity.TicketDO;
import com.ticketai.mapper.ChannelMapper;
import com.ticketai.mapper.ChannelMessageMapper;
import com.ticketai.mapper.TicketMapper;
import com.ticketai.service.TicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 渠道幂等创建测试（DEV_DOC M2 DoD：重复 messageNo 两次请求返回同一工单）。
 */
@ExtendWith(MockitoExtension.class)
class ChannelServiceImplTest {

    @Mock
    private ChannelMapper channelMapper;
    @Mock
    private ChannelMessageMapper channelMessageMapper;
    @Mock
    private TicketService ticketService;
    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;
    @Mock
    private org.springframework.data.redis.core.ValueOperations<String, String> valueOperations;

    private ChannelServiceImpl newService() {
        return new ChannelServiceImpl(channelMapper, channelMessageMapper, ticketService,
                ticketMapper, stringRedisTemplate);
    }

    private ChannelTicketCreateDTO dto() {
        ChannelTicketCreateDTO dto = new ChannelTicketCreateDTO();
        dto.setMessageNo("MSG-001");
        dto.setTitle("退款申请");
        dto.setContent("客户要求退款");
        dto.setCustomerName("张三");
        return dto;
    }

    private ChannelDO webApiChannel() {
        ChannelDO channel = new ChannelDO();
        channel.setId(1L);
        channel.setCode("WEB_API");
        channel.setStatus(1);
        return channel;
    }

    @Test
    @DisplayName("首次创建：落渠道消息 + 建工单 + 回填关联")
    void firstCreate() {
        when(channelMapper.selectOne(any())).thenReturn(webApiChannel());
        when(channelMessageMapper.selectOne(any())).thenReturn(null);
        when(ticketService.create(any())).thenAnswer(inv -> {
            TicketDO t = new TicketDO();
            t.setId(10L);
            t.setTicketNo("T20260816000010");
            return t;
        });
        when(ticketMapper.updateById(any(TicketDO.class))).thenReturn(1);

        Map<String, Object> result = newService().webApiCreateTicket(dto());

        assertEquals(10L, result.get("id"));
        assertEquals("T20260816000010", result.get("ticketNo"));
        verify(channelMessageMapper).insert(any(ChannelMessageDO.class));
        verify(ticketService).create(any());
        verify(ticketMapper).updateById(any(TicketDO.class));
        verify(channelMessageMapper).updateById(any(ChannelMessageDO.class));
    }

    @Test
    @DisplayName("幂等命中：messageNo 已存在且有关联工单 → 直接返回已建工单，不重复创建")
    void idempotentHit() {
        when(channelMapper.selectOne(any())).thenReturn(webApiChannel());
        ChannelMessageDO existed = new ChannelMessageDO();
        existed.setId(5L);
        existed.setMessageNo("MSG-001");
        existed.setTicketId(10L);
        when(channelMessageMapper.selectOne(any())).thenReturn(existed);
        TicketDO ticket = new TicketDO();
        ticket.setId(10L);
        ticket.setTicketNo("T20260816000010");
        when(ticketMapper.selectById(10L)).thenReturn(ticket);

        Map<String, Object> result = newService().webApiCreateTicket(dto());

        assertEquals("T20260816000010", result.get("ticketNo"));
        verify(ticketService, never()).create(any());
        verify(channelMessageMapper, never()).insert(any(ChannelMessageDO.class));
    }

    @Test
    @DisplayName("并发冲突：唯一键冲突抛 DuplicateKeyException → 查回已建工单")
    void concurrentDuplicate() {
        when(channelMapper.selectOne(any())).thenReturn(webApiChannel());
        when(channelMessageMapper.selectOne(any()))
                .thenReturn(null)                       // 首次查无
                .thenReturn(existedMessage())           // 冲突后查回
                .thenReturn(existedMessage());
        when(channelMessageMapper.insert(any(ChannelMessageDO.class))).thenThrow(new DuplicateKeyException("uk_message_no"));
        TicketDO ticket = new TicketDO();
        ticket.setId(10L);
        ticket.setTicketNo("T20260816000010");
        when(ticketMapper.selectById(10L)).thenReturn(ticket);

        Map<String, Object> result = newService().webApiCreateTicket(dto());

        assertNotNull(result);
        assertEquals("T20260816000010", result.get("ticketNo"));
        verify(ticketService, never()).create(any());
    }

    private ChannelMessageDO existedMessage() {
        ChannelMessageDO m = new ChannelMessageDO();
        m.setId(5L);
        m.setMessageNo("MSG-001");
        m.setTicketId(10L);
        return m;
    }

    // ---------- P0-2 渠道鉴权 ----------

    @Test
    @DisplayName("P0-2：缺失渠道凭证 → 401")
    void missingCredentialRejected() {
        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> newService().assertWebApiRequest(null));
        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("P0-2：无效渠道凭证 → 401")
    void invalidCredentialRejected() {
        when(channelMapper.selectOne(any())).thenReturn(null);
        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> newService().assertWebApiRequest("Bearer wrong-key"));
        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("P0-2：有效凭证 + 超额请求 → 429 限流")
    void overRateLimitRejected() {
        ChannelDO channel = webApiChannel();
        channel.setAppKey("dev-channel-key");
        when(channelMapper.selectOne(any())).thenReturn(channel);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(any())).thenReturn(601L);

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> newService().assertWebApiRequest("Bearer dev-channel-key"));
        assertEquals(429, ex.getCode());
    }

    @Test
    @DisplayName("P0-2：有效凭证 + 首次请求 → 放行")
    void validCredentialPassed() {
        ChannelDO channel = webApiChannel();
        channel.setAppKey("dev-channel-key");
        when(channelMapper.selectOne(any())).thenReturn(channel);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(any())).thenReturn(1L);

        newService().assertWebApiRequest("Bearer dev-channel-key"); // 不抛异常即通过

        verify(stringRedisTemplate).expire(any(), anyLong(), any());
    }
}
