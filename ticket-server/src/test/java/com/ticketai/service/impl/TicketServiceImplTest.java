package com.ticketai.service.impl;

import com.ticketai.common.exception.BusinessException;
import com.ticketai.common.exception.ErrorCode;
import com.ticketai.common.util.TicketNoGenerator;
import com.ticketai.dto.TicketCreateDTO;
import com.ticketai.entity.TicketDO;
import com.ticketai.entity.TicketStatusLogDO;
import com.ticketai.mapper.TicketMapper;
import com.ticketai.mapper.TicketStatusLogMapper;
import com.ticketai.security.LoginUser;
import com.ticketai.security.UserContextHolder;
import com.ticketai.service.SlaService;
import com.ticketai.state.TicketEvent;
import com.ticketai.state.TicketStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工单服务测试（DEV_DOC M1 DoD）：乐观锁冲突、权限校验、非法流转。
 * 全部 mock，不连接真实中间件。
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceImplTest {

    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private TicketStatusLogMapper ticketStatusLogMapper;
    @Mock
    private TicketNoGenerator ticketNoGenerator;
    @Mock
    private SlaService slaService;
    @Mock
    private org.redisson.api.RedissonClient redissonClient;
    @Mock
    private com.ticketai.mapper.AgentMapper agentMapper;
    @Mock
    private com.ticketai.mapper.TicketCommentMapper ticketCommentMapper;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private TicketServiceImpl ticketService;

    @BeforeEach
    void setUp() {
        ticketService = new TicketServiceImpl(ticketMapper, ticketStatusLogMapper, ticketNoGenerator,
                slaService, eventPublisher, redissonClient, agentMapper, ticketCommentMapper);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    private TicketDO pendingAssignTicket(Long id) {
        TicketDO ticket = new TicketDO();
        ticket.setId(id);
        ticket.setTicketNo("T20260816000001");
        ticket.setStatus(TicketStatus.PENDING_ASSIGN.getCode());
        ticket.setVersion(0);
        return ticket;
    }

    private void loginAs(String username, String... permissions) {
        UserContextHolder.set(new LoginUser(1L, username, null, List.of(permissions)));
    }

    @Test
    @DisplayName("创建工单：状态为待分派并写 SUBMIT 日志")
    void createTicket() {
        when(ticketNoGenerator.next()).thenReturn("T20260816000001");
        loginAs("admin", "ticket:view");

        TicketCreateDTO dto = new TicketCreateDTO();
        dto.setTitle("测试工单");
        dto.setPriority(3);
        dto.setChannelId(1L);

        TicketDO created = ticketService.create(dto);

        assertEquals(TicketStatus.PENDING_ASSIGN.getCode(), created.getStatus());
        assertEquals("T20260816000001", created.getTicketNo());
        ArgumentCaptor<TicketStatusLogDO> logCaptor = ArgumentCaptor.forClass(TicketStatusLogDO.class);
        verify(ticketStatusLogMapper).insert(logCaptor.capture());
        assertEquals(TicketEvent.SUBMIT.name(), logCaptor.getValue().getEvent());
        assertEquals(TicketStatus.NEW.getCode(), logCaptor.getValue().getFromStatus());
        assertEquals(TicketStatus.PENDING_ASSIGN.getCode(), logCaptor.getValue().getToStatus());
    }

    @Test
    @DisplayName("合法流转：待分派 + CLAIM → 处理中")
    void legalClaimTransition() {
        when(ticketMapper.selectById(1L)).thenReturn(pendingAssignTicket(1L));
        when(ticketMapper.update(any(), any())).thenReturn(1);
        loginAs("agent01", "ticket:view", "ticket:claim");

        TicketDO result = ticketService.transition(1L, TicketEvent.CLAIM, 1L, "USER");

        assertEquals(TicketStatus.PROCESSING.getCode(), result.getStatus());
        verify(ticketStatusLogMapper).insert(any(TicketStatusLogDO.class));
    }

    @Test
    @DisplayName("非法流转：CLOSED 状态再 CLOSE → ILLEGAL_TRANSITION")
    void illegalTransition() {
        TicketDO closed = pendingAssignTicket(1L);
        closed.setStatus(TicketStatus.CLOSED.getCode());
        when(ticketMapper.selectById(1L)).thenReturn(closed);
        loginAs("admin", "ticket:close");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ticketService.transition(1L, TicketEvent.CLOSE, 1L, "USER"));
        assertEquals(ErrorCode.ILLEGAL_TRANSITION.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("权限不足：无 ticket:claim 权限触发 CLAIM → FORBIDDEN")
    void insufficientPermission() {
        when(ticketMapper.selectById(1L)).thenReturn(pendingAssignTicket(1L));
        loginAs("viewer", "ticket:view");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ticketService.transition(1L, TicketEvent.CLAIM, 1L, "USER"));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("乐观锁冲突：updateById 影响 0 行 → CONCURRENT_MODIFY")
    void optimisticLockConflict() {
        when(ticketMapper.selectById(1L)).thenReturn(pendingAssignTicket(1L));
        when(ticketMapper.update(any(), any())).thenReturn(0);
        loginAs("agent01", "ticket:claim");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ticketService.transition(1L, TicketEvent.CLAIM, 1L, "USER"));
        assertEquals(ErrorCode.CONCURRENT_MODIFY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("工单不存在 → NOT_FOUND")
    void ticketNotFound() {
        when(ticketMapper.selectById(99L)).thenReturn(null);
        loginAs("admin", "ticket:view");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ticketService.transition(99L, TicketEvent.CLOSE, 1L, "USER"));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("首次回复置 firstRespondedAt（SLA 计时停止）")
    void firstReplySetsRespondedAt() {
        TicketDO processing = pendingAssignTicket(1L);
        processing.setStatus(TicketStatus.PROCESSING.getCode());
        when(ticketMapper.selectById(1L)).thenReturn(processing);
        when(ticketMapper.update(any(), any())).thenReturn(1);
        loginAs("agent01", "ticket:reply");

        TicketDO result = ticketService.transition(1L, TicketEvent.REPLY, 1L, "USER");

        assertEquals(TicketStatus.WAITING_CUSTOMER.getCode(), result.getStatus());
        assertNotNull(result.getFirstRespondedAt(), "首次回复应记录 firstRespondedAt");
    }

    @Test
    @DisplayName("解决置 resolvedAt、关闭置 closedAt")
    void resolveAndCloseTimestamps() {
        TicketDO processing = pendingAssignTicket(1L);
        processing.setStatus(TicketStatus.PROCESSING.getCode());
        when(ticketMapper.selectById(1L)).thenReturn(processing);
        when(ticketMapper.update(any(), any())).thenReturn(1);
        loginAs("agent01", "ticket:resolve");

        TicketDO resolved = ticketService.transition(1L, TicketEvent.RESOLVE, 1L, "USER");
        assertNotNull(resolved.getResolvedAt());

        TicketDO resolvedState = pendingAssignTicket(1L);
        resolvedState.setStatus(TicketStatus.RESOLVED.getCode());
        resolvedState.setResolvedAt(java.time.LocalDateTime.of(2026, 8, 16, 10, 0));
        when(ticketMapper.selectById(1L)).thenReturn(resolvedState);
        loginAs("agent01", "ticket:close");

        TicketDO closed = ticketService.transition(1L, TicketEvent.CLOSE, 1L, "USER");
        assertNotNull(closed.getClosedAt());
        assertNotNull(closed.getResolvedAt(), "close 应保留 resolvedAt，仅 reopen 清空");
    }
}
