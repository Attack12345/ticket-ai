package com.ticketai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分派策略配置（dispatch_strategy）
 */
@Data
@TableName("dispatch_strategy")
public class DispatchStrategyDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 策略编码：ROUND_ROBIN / LEAST_LOADED / SKILL_MATCH / AI_RECOMMEND */
    private String strategyType;

    /** 权重：启用策略按权重降序尝试 */
    private Integer weight;

    /** 启用：0-否 1-是 */
    private Integer enabled;

    /** 策略参数 JSON */
    private String paramJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
