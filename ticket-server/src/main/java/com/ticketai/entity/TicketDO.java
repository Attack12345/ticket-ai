package com.ticketai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工单主表（ticket）。状态变更必须走 TicketService#transition，禁止直接改 status。
 */
@Data
@TableName("ticket")
public class TicketDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单编号：T+yyyyMMdd+6位序列 */
    private String ticketNo;

    private Long channelId;

    private Long channelMessageId;

    private String title;

    private String description;

    /** 最终分类（坐席确认后写入） */
    private String category;

    /** 优先级：1-紧急 2-高 3-中 4-低 */
    private Integer priority;

    /** 状态码，见 TicketStatus 枚举（2-待分派） */
    private Integer status;

    private Long groupId;

    private Long agentId;

    /** 实际采用的分派策略编码 */
    private String assignStrategy;

    private String customerName;

    private String customerContact;

    private Long slaPolicyId;

    private LocalDateTime firstResponseDeadline;

    private LocalDateTime resolveDeadline;

    private LocalDateTime firstRespondedAt;

    private LocalDateTime resolvedAt;

    private LocalDateTime closedAt;

    /** AI 分类建议 */
    private String aiCategory;

    /** AI 优先级建议 */
    private Integer aiPriority;

    /** AI 分类置信度(0-1) */
    private BigDecimal aiScore;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    private String remark;
}
