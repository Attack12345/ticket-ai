package com.ticketai.state;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 状态转移矩阵注册表（DEV_DOC §5.1.3 权威矩阵，共 22 条，禁止增删）。
 * 静态块一次性初始化，运行时只读。
 */
public final class StateMachineRegistry {

    private static final String SYSTEM = "SYSTEM";
    private static final Map<TicketStatus, Map<TicketEvent, Transition>> TRANSITIONS = new EnumMap<>(TicketStatus.class);

    static {
        register(TicketStatus.NEW, TicketEvent.SUBMIT, TicketStatus.PENDING_ASSIGN, "ticket:view");
        register(TicketStatus.NEW, TicketEvent.CANCEL, TicketStatus.CANCELLED, "ticket:view");

        register(TicketStatus.PENDING_ASSIGN, TicketEvent.AUTO_ASSIGN, TicketStatus.PROCESSING, SYSTEM);
        register(TicketStatus.PENDING_ASSIGN, TicketEvent.MANUAL_ASSIGN, TicketStatus.PROCESSING, "ticket:assign");
        register(TicketStatus.PENDING_ASSIGN, TicketEvent.CLAIM, TicketStatus.PROCESSING, "ticket:claim");
        register(TicketStatus.PENDING_ASSIGN, TicketEvent.ESCALATE, TicketStatus.ESCALATED, "ticket:escalate");
        register(TicketStatus.PENDING_ASSIGN, TicketEvent.TIMEOUT_ESCALATE, TicketStatus.ESCALATED, SYSTEM);
        register(TicketStatus.PENDING_ASSIGN, TicketEvent.CANCEL, TicketStatus.CANCELLED, "ticket:close");

        register(TicketStatus.PROCESSING, TicketEvent.REPLY, TicketStatus.WAITING_CUSTOMER, "ticket:reply");
        register(TicketStatus.PROCESSING, TicketEvent.RESOLVE, TicketStatus.RESOLVED, "ticket:resolve");
        register(TicketStatus.PROCESSING, TicketEvent.ESCALATE, TicketStatus.ESCALATED, "ticket:escalate");
        register(TicketStatus.PROCESSING, TicketEvent.TIMEOUT_ESCALATE, TicketStatus.ESCALATED, SYSTEM);
        register(TicketStatus.PROCESSING, TicketEvent.CANCEL, TicketStatus.CANCELLED, "ticket:close");

        register(TicketStatus.WAITING_CUSTOMER, TicketEvent.CUSTOMER_REPLY, TicketStatus.PROCESSING, SYSTEM);
        register(TicketStatus.WAITING_CUSTOMER, TicketEvent.REPLY, TicketStatus.WAITING_CUSTOMER, "ticket:reply");
        register(TicketStatus.WAITING_CUSTOMER, TicketEvent.RESOLVE, TicketStatus.RESOLVED, "ticket:resolve");
        register(TicketStatus.WAITING_CUSTOMER, TicketEvent.TIMEOUT_ESCALATE, TicketStatus.ESCALATED, SYSTEM);

        register(TicketStatus.RESOLVED, TicketEvent.REOPEN, TicketStatus.PROCESSING, "ticket:resolve");
        register(TicketStatus.RESOLVED, TicketEvent.CLOSE, TicketStatus.CLOSED, "ticket:close");

        register(TicketStatus.CLOSED, TicketEvent.REOPEN, TicketStatus.PROCESSING, "ticket:assign");

        register(TicketStatus.ESCALATED, TicketEvent.MANUAL_ASSIGN, TicketStatus.PROCESSING, "ticket:assign");
        register(TicketStatus.ESCALATED, TicketEvent.RESOLVE, TicketStatus.RESOLVED, "ticket:resolve");
        register(TicketStatus.ESCALATED, TicketEvent.CANCEL, TicketStatus.CANCELLED, "ticket:close");

        // CANCELLED 无出口，不注册任何转移
    }

    private StateMachineRegistry() {
    }

    private static void register(TicketStatus from, TicketEvent event, TicketStatus to, String requiredPermission) {
        TRANSITIONS
                .computeIfAbsent(from, k -> new EnumMap<>(TicketEvent.class))
                .put(event, new Transition(from, event, to, requiredPermission));
    }

    /** 查询转移定义，不存在返回 null */
    public static Transition find(TicketStatus from, TicketEvent event) {
        Map<TicketEvent, Transition> byEvent = TRANSITIONS.get(from);
        return byEvent == null ? null : byEvent.get(event);
    }

    /** 当前状态允许的全部事件（只读视图） */
    public static Set<TicketEvent> allowedEvents(TicketStatus from) {
        Map<TicketEvent, Transition> byEvent = TRANSITIONS.get(from);
        return byEvent == null ? Collections.emptySet() : Collections.unmodifiableSet(byEvent.keySet());
    }
}
