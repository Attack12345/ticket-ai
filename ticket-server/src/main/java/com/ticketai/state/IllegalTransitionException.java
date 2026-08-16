package com.ticketai.state;

import lombok.Getter;

/**
 * 非法状态流转异常（引擎层抛出）。
 * 业务层应在调用 fire 前用 canTransition 校验，并转为 BusinessException(ILLEGAL_TRANSITION)。
 */
@Getter
public class IllegalTransitionException extends RuntimeException {

    private final TicketStatus from;
    private final TicketEvent event;

    public IllegalTransitionException(TicketStatus from, TicketEvent event) {
        super(String.format("非法的工单状态流转: %s --%s--> ?", from.getDesc(), event.getDesc()));
        this.from = from;
        this.event = event;
    }
}
