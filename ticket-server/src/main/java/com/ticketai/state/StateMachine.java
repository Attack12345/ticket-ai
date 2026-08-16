package com.ticketai.state;

import java.util.Set;

/**
 * 状态机引擎核心（DEV_DOC §5.1.4）。
 * 只负责状态合法性判定与流转，权限校验由调用方基于 Transition.requiredPermission 完成。
 */
public final class StateMachine {

    private StateMachine() {
    }

    /** 该状态下事件是否可触发 */
    public static boolean canTransition(TicketStatus from, TicketEvent event) {
        return StateMachineRegistry.find(from, event) != null;
    }

    /** 获取转移定义，不可触发返回 null */
    public static Transition getTransition(TicketStatus from, TicketEvent event) {
        return StateMachineRegistry.find(from, event);
    }

    /** 执行流转，返回目标状态；非法流转抛 IllegalTransitionException */
    public static TicketStatus fire(TicketStatus from, TicketEvent event) {
        Transition transition = StateMachineRegistry.find(from, event);
        if (transition == null) {
            throw new IllegalTransitionException(from, event);
        }
        return transition.to();
    }

    /** 当前状态允许触发的事件集合 */
    public static Set<TicketEvent> allowedEvents(TicketStatus from) {
        return StateMachineRegistry.allowedEvents(from);
    }
}
