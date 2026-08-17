package com.ticketai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 调用记录（ai_usage_log）。每次调用成功/失败必写（DEV_DOC §5.5.1）。
 */
@Data
@TableName("ai_usage_log")
public class AiUsageLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    /** 场景：CLASSIFY-分类 SUGGEST-回复建议 DISPATCH-分派 EMBED-向量化 */
    private String scene;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Integer latencyMs;

    /** 是否成功：0-否 1-是 */
    private Integer success;

    private String errorMsg;

    private LocalDateTime createTime;
}
