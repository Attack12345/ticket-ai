package com.ticketai.service.impl;

import com.ticketai.common.exception.BusinessException;
import com.ticketai.common.exception.ErrorCode;
import com.ticketai.entity.AgentDO;
import com.ticketai.entity.TicketDO;
import com.ticketai.entity.TicketStatusLogDO;
import com.ticketai.mapper.AgentMapper;
import com.ticketai.mapper.TicketMapper;
import com.ticketai.mapper.TicketStatusLogMapper;
import com.ticketai.security.LoginUser;
import com.ticketai.security.UserContextHolder;
import com.ticketai.state.TicketStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 抢单并发测试（DEV_DOC M4 DoD）：10 线程同时抢同一张工单，恰好 1 个成功、9 个 CONCURRENT_MODIFY。
 * 乐观锁是最终防线：mock update 首次返回 1、其余返回 0（等价于真实 DB 的行锁+版本条件）。
 */
@ExtendWith(MockitoExtension.class)
class TicketClaimConcurrentTest {

    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private TicketStatusLogMapper ticketStatusLogMapper;
    @Mock
    private AgentMapper agentMapper;
    @Mock
    private RedissonClient redissonClient;

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("10 线程并发抢单：恰好 1 个成功，9 个 CONCURRENT_MODIFY")
    void tenThreadsClaimOneTicket() throws Exception {
        // 锁可获取（mock），乐观锁 update 首个成功、其余失败
        RLock lock = mock(RLock.class);
        when(lock.tryLock(3, 10, TimeUnit.SECONDS)).thenReturn(true);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(ticketMapper.selectById(1L)).thenReturn(pendingTicket());
        when(ticketMapper.update(any(), any())).thenReturn(1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        when(ticketStatusLogMapper.insert(any(TicketStatusLogDO.class))).thenReturn(1);
        when(agentMapper.update(any(), any())).thenReturn(1);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        TicketServiceImpl service = new TicketServiceImpl(ticketMapper, ticketStatusLogMapper, null,
                null, null, redissonClient, agentMapper, null);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                UserContextHolder.set(new LoginUser(2L, "agent01", 100L, List.of("ticket:claim")));
                ready.countDown();
                try {
                    start.await();
                    service.claim(1L);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getCode() == ErrorCode.CONCURRENT_MODIFY.getCode()) {
                        conflict.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 其他异常忽略
                } finally {
                    UserContextHolder.clear();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(1, success.get(), "恰好 1 个线程抢单成功");
        assertEquals(9, conflict.get(), "其余 9 个线程应收到 CONCURRENT_MODIFY");
    }

    private TicketDO pendingTicket() {
        TicketDO ticket = new TicketDO();
        ticket.setId(1L);
        ticket.setTicketNo("T20260816000001");
        ticket.setStatus(TicketStatus.PENDING_ASSIGN.getCode());
        ticket.setVersion(0);
        return ticket;
    }
}
