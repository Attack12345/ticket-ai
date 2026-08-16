package com.ticketai.service.impl;

import com.ticketai.entity.SlaPolicyDO;
import com.ticketai.entity.TicketDO;
import com.ticketai.entity.TicketSlaDO;
import com.ticketai.event.SlaTimeoutEvent;
import com.ticketai.mapper.AuditLogMapper;
import com.ticketai.mapper.SlaPolicyMapper;
import com.ticketai.mapper.TicketMapper;
import com.ticketai.mapper.TicketSlaMapper;
import com.ticketai.mq.SlaDelayProducer;
import com.ticketai.mq.SlaMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SLA 引擎结算/补偿测试（DEV_DOC M3 DoD）。
 */
@ExtendWith(MockitoExtension.class)
class SlaServiceImplTest {

    @Mock
    private SlaPolicyMapper slaPolicyMapper;
    @Mock
    private TicketSlaMapper ticketSlaMapper;
    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private AuditLogMapper auditLogMapper;
    @Mock
    private SlaDelayProducer slaDelayProducer;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private SlaServiceImpl newService() {
        return new SlaServiceImpl(slaPolicyMapper, ticketSlaMapper, ticketMapper,
                auditLogMapper, slaDelayProducer, eventPublisher);
    }

    private TicketSlaDO overdueSla(Long id, Long ticketId) {
        TicketSlaDO sla = new TicketSlaDO();
        sla.setId(id);
        sla.setTicketId(ticketId);
        sla.setFirstResponseDeadline(LocalDateTime.now().minusMinutes(5));
        sla.setResolveDeadline(LocalDateTime.now().plusHours(1));
        sla.setFirstResponseStatus(0);
        sla.setResolveStatus(0);
        sla.setEscalationTriggered(0);
        return sla;
    }

    private TicketDO pendingTicket(Long id) {
        TicketDO ticket = new TicketDO();
        ticket.setId(id);
        ticket.setTicketNo("T20260816000001");
        ticket.setStatus(2);
        return ticket;
    }

    @Test
    @DisplayName("已响应工单收到延迟消息 → 标记按时，不升级")
    void alreadyRespondedNoEscalation() {
        TicketSlaDO sla = overdueSla(1L, 10L);
        sla.setFirstRespondedAt(LocalDateTime.now());
        sla.setFirstResponseStatus(1);
        when(ticketSlaMapper.selectById(1L)).thenReturn(sla);
        when(ticketMapper.selectById(10L)).thenReturn(pendingTicket(10L));

        newService().handleDelayCheck(new SlaMessage(10L, 1L, SlaMessage.TYPE_FIRST_RESPONSE, LocalDateTime.now()));

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("未响应且超时 → 标记超时并发布升级事件")
    void timeoutTriggersEscalation() {
        TicketSlaDO sla = overdueSla(1L, 10L);
        when(ticketSlaMapper.selectById(1L)).thenReturn(sla);
        when(ticketMapper.selectById(10L)).thenReturn(pendingTicket(10L));

        newService().handleDelayCheck(new SlaMessage(10L, 1L, SlaMessage.TYPE_FIRST_RESPONSE, LocalDateTime.now()));

        assertEquals(2, sla.getFirstResponseStatus());
        assertEquals(1, sla.getEscalationTriggered());
        assertNotNull(sla.getEscalatedAt());
        ArgumentCaptor<SlaTimeoutEvent> captor = ArgumentCaptor.forClass(SlaTimeoutEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(10L, captor.getValue().ticketId());
        verify(auditLogMapper).insert(any(com.ticketai.entity.AuditLogDO.class));
    }

    @Test
    @DisplayName("已升级过的消息重复到达 → 幂等忽略")
    void alreadyEscalatedIgnored() {
        TicketSlaDO sla = overdueSla(1L, 10L);
        sla.setEscalationTriggered(1);
        when(ticketSlaMapper.selectById(1L)).thenReturn(sla);

        newService().handleDelayCheck(new SlaMessage(10L, 1L, SlaMessage.TYPE_FIRST_RESPONSE, LocalDateTime.now()));

        verify(eventPublisher, never()).publishEvent(any());
        verify(ticketMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("补偿扫描：消息丢失后，超时未升级记录仍被扫出并升级（兜底）")
    void compensateCatchesLostMessage() {
        TicketSlaDO sla = overdueSla(1L, 10L);
        when(ticketSlaMapper.selectList(any())).thenReturn(java.util.List.of(sla));
        when(ticketSlaMapper.selectById(1L)).thenReturn(sla);
        when(ticketMapper.selectById(10L)).thenReturn(pendingTicket(10L));

        newService().compensate();

        verify(eventPublisher).publishEvent(any(SlaTimeoutEvent.class));
    }

    @Test
    @DisplayName("createTicketSla：建计时实例并投递响应/解决两条延迟消息")
    void createTicketSlaSendsTwoMessages() {
        SlaPolicyDO policy = new SlaPolicyDO();
        policy.setId(3L);
        policy.setFirstResponseMinutes(15);
        policy.setResolveMinutes(240);
        policy.setAutoEscalate(1);
        when(slaPolicyMapper.selectOne(any())).thenReturn(policy);
        when(ticketSlaMapper.insert(any(TicketSlaDO.class))).thenAnswer(inv -> {
            inv.<TicketSlaDO>getArgument(0).setId(9L);
            return 1;
        });

        newService().createTicketSla(10L, 3);

        verify(slaDelayProducer, times(2)).send(any(Long.class), any(Long.class), any(String.class), anyLong());
        ArgumentCaptor<TicketSlaDO> captor = ArgumentCaptor.forClass(TicketSlaDO.class);
        verify(ticketSlaMapper).insert(captor.capture());
        assertNotNull(captor.getValue().getFirstResponseDeadline());
        assertNotNull(captor.getValue().getResolveDeadline());
    }
}
