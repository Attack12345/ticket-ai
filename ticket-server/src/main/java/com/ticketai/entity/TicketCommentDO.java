package com.ticketai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单评论/回复（ticket_comment）
 */
@Data
@TableName("ticket_comment")
public class TicketCommentDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    private Long agentId;

    /** 类型：REPLY-回复客户 INTERNAL-内部备注 */
    private String type;

    private String content;

    /** 可见性：ALL-客户可见 INTERNAL-仅内部 */
    private String visibility;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
