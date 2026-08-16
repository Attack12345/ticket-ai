package com.ticketai.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机测试（DEV_DOC M1 DoD）：
 * 覆盖矩阵全部 23 条合法转移 + 全量非法组合断言。
 */
class StateMachineTest {

    /** 权威矩阵：23 条合法转移（DEV_DOC §5.1.3） */
    private static final List<Transition> EXPECTED = List.of(
            new Transition(TicketStatus.NEW, TicketEvent.SUBMIT, TicketStatus.PENDING_ASSIGN, "ticket:view"),
            new Transition(TicketStatus.NEW, TicketEvent.CANCEL, TicketStatus.CANCELLED, "ticket:view"),

            new Transition(TicketStatus.PENDING_ASSIGN, TicketEvent.AUTO_ASSIGN, TicketStatus.PROCESSING, "SYSTEM"),
            new Transition(TicketStatus.PENDING_ASSIGN, TicketEvent.MANUAL_ASSIGN, TicketStatus.PROCESSING, "ticket:assign"),
            new Transition(TicketStatus.PENDING_ASSIGN, TicketEvent.CLAIM, TicketStatus.PROCESSING, "ticket:claim"),
            new Transition(TicketStatus.PENDING_ASSIGN, TicketEvent.ESCALATE, TicketStatus.ESCALATED, "ticket:escalate"),
            new Transition(TicketStatus.PENDING_ASSIGN, TicketEvent.TIMEOUT_ESCALATE, TicketStatus.ESCALATED, "SYSTEM"),
            new Transition(TicketStatus.PENDING_ASSIGN, TicketEvent.CANCEL, TicketStatus.CANCELLED, "ticket:close"),

            new Transition(TicketStatus.PROCESSING, TicketEvent.REPLY, TicketStatus.WAITING_CUSTOMER, "ticket:reply"),
            new Transition(TicketStatus.PROCESSING, TicketEvent.RESOLVE, TicketStatus.RESOLVED, "ticket:resolve"),
            new Transition(TicketStatus.PROCESSING, TicketEvent.ESCALATE, TicketStatus.ESCALATED, "ticket:escalate"),
            new Transition(TicketStatus.PROCESSING, TicketEvent.TIMEOUT_ESCALATE, TicketStatus.ESCALATED, "SYSTEM"),
            new Transition(TicketStatus.PROCESSING, TicketEvent.CANCEL, TicketStatus.CANCELLED, "ticket:close"),

            new Transition(TicketStatus.WAITING_CUSTOMER, TicketEvent.CUSTOMER_REPLY, TicketStatus.PROCESSING, "SYSTEM"),
            new Transition(TicketStatus.WAITING_CUSTOMER, TicketEvent.REPLY, TicketStatus.WAITING_CUSTOMER, "ticket:reply"),
            new Transition(TicketStatus.WAITING_CUSTOMER, TicketEvent.RESOLVE, TicketStatus.RESOLVED, "ticket:resolve"),
            new Transition(TicketStatus.WAITING_CUSTOMER, TicketEvent.TIMEOUT_ESCALATE, TicketStatus.ESCALATED, "SYSTEM"),

            new Transition(TicketStatus.RESOLVED, TicketEvent.REOPEN, TicketStatus.PROCESSING, "ticket:resolve"),
            new Transition(TicketStatus.RESOLVED, TicketEvent.CLOSE, TicketStatus.CLOSED, "ticket:close"),

            new Transition(TicketStatus.CLOSED, TicketEvent.REOPEN, TicketStatus.PROCESSING, "ticket:assign"),

            new Transition(TicketStatus.ESCALATED, TicketEvent.MANUAL_ASSIGN, TicketStatus.PROCESSING, "ticket:assign"),
            new Transition(TicketStatus.ESCALATED, TicketEvent.RESOLVE, TicketStatus.RESOLVED, "ticket:resolve"),
            new Transition(TicketStatus.ESCALATED, TicketEvent.CANCEL, TicketStatus.CANCELLED, "ticket:close"));

    @Test
    @DisplayName("全部 23 条合法转移可触发且目标状态/权限正确")
    void allLegalTransitions() {
        assertEquals(23, EXPECTED.size(), "权威矩阵应恰好 23 条");
        for (Transition expected : EXPECTED) {
            Transition actual = StateMachine.getTransition(expected.from(), expected.event());
            assertNotNull(actual, "缺失转移: " + expected.from() + " -> " + expected.event());
            assertEquals(expected.to(), actual.to());
            assertEquals(expected.requiredPermission(), actual.requiredPermission());
            assertTrue(StateMachine.canTransition(expected.from(), expected.event()));
            assertEquals(expected.to(), StateMachine.fire(expected.from(), expected.event()),
                    "fire 结果与矩阵不一致: " + expected);
        }
    }

    @Test
    @DisplayName("全量非法组合断言：8状态×12事件中非矩阵组合全部拒绝")
    void allIllegalCombinations() {
        int legal = 0;
        for (TicketStatus from : TicketStatus.values()) {
            for (TicketEvent event : TicketEvent.values()) {
                if (StateMachine.canTransition(from, event)) {
                    legal++;
                } else {
                    assertFalse(StateMachine.canTransition(from, event));
                    IllegalTransitionException ex = assertThrows(IllegalTransitionException.class,
                            () -> StateMachine.fire(from, event),
                            "应拒绝非法流转: " + from + " + " + event);
                    assertEquals(from, ex.getFrom());
                    assertEquals(event, ex.getEvent());
                }
            }
        }
        assertEquals(23, legal, "合法转移总数应为 23");
    }

    @Test
    @DisplayName("代表性非法流转显式断言（覆盖各状态）")
    void representativeIllegalTransitions() {
        assertIllegal(TicketStatus.NEW, TicketEvent.REPLY);
        assertIllegal(TicketStatus.NEW, TicketEvent.RESOLVE);
        assertIllegal(TicketStatus.PENDING_ASSIGN, TicketEvent.REPLY);
        assertIllegal(TicketStatus.PENDING_ASSIGN, TicketEvent.SUBMIT);
        assertIllegal(TicketStatus.PROCESSING, TicketEvent.SUBMIT);
        assertIllegal(TicketStatus.PROCESSING, TicketEvent.CLAIM);
        assertIllegal(TicketStatus.WAITING_CUSTOMER, TicketEvent.CLAIM);
        assertIllegal(TicketStatus.WAITING_CUSTOMER, TicketEvent.SUBMIT);
        assertIllegal(TicketStatus.RESOLVED, TicketEvent.REPLY);
        assertIllegal(TicketStatus.RESOLVED, TicketEvent.RESOLVE);
        assertIllegal(TicketStatus.CLOSED, TicketEvent.CLOSE);
        assertIllegal(TicketStatus.CLOSED, TicketEvent.CANCEL);
        assertIllegal(TicketStatus.ESCALATED, TicketEvent.CLAIM);
        assertIllegal(TicketStatus.CANCELLED, TicketEvent.REOPEN);
        assertIllegal(TicketStatus.CANCELLED, TicketEvent.CLOSE);
        assertIllegal(TicketStatus.CANCELLED, TicketEvent.SUBMIT);
    }

    @Test
    @DisplayName("CANCELLED 无出口：allowedEvents 为空")
    void cancelledHasNoExit() {
        assertTrue(StateMachine.allowedEvents(TicketStatus.CANCELLED).isEmpty());
        assertTrue(StateMachine.allowedEvents(TicketStatus.CLOSED).contains(TicketEvent.REOPEN));
        assertTrue(StateMachine.allowedEvents(TicketStatus.PROCESSING).contains(TicketEvent.TIMEOUT_ESCALATE));
    }

    private void assertIllegal(TicketStatus from, TicketEvent event) {
        assertFalse(StateMachine.canTransition(from, event), "不应允许: " + from + " + " + event);
        assertThrows(IllegalTransitionException.class, () -> StateMachine.fire(from, event));
    }
}
