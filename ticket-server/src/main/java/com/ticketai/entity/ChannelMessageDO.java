package com.ticketai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道原始消息（channel_message）。message_no 唯一键保证幂等。
 */
@Data
@TableName("channel_message")
public class ChannelMessageDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long channelId;

    /** 渠道消息号（幂等键） */
    private String messageNo;

    /** 关联工单 ID（回复时回填） */
    private Long ticketId;

    /** 方向：1-客户进线 2-坐席回复 */
    private Integer direction;

    private String customerName;

    private String customerContact;

    private String title;

    private String content;

    /** 原始报文 */
    private String rawJson;

    private LocalDateTime createTime;
}
