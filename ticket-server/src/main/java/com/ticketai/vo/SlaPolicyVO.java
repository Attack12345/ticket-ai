package com.ticketai.vo;

import lombok.Data;

/**
 * SLA 策略 VO
 */
@Data
public class SlaPolicyVO {

    private Long id;
    private String name;
    private Integer priority;
    private Integer firstResponseMinutes;
    private Integer resolveMinutes;
    private Integer autoEscalate;
    private String escalateAction;
    private Integer status;
    private String remark;
}
