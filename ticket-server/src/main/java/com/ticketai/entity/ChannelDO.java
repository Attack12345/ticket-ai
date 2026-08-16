package com.ticketai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接入渠道（channel）
 */
@Data
@TableName("channel")
public class ChannelDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 渠道编码：WEB_API-网页接口 EMAIL-邮件 */
    private String code;

    private String name;

    /** 渠道配置 JSON（如邮件 IMAP 参数） */
    private String configJson;

    /** 状态：0-禁用 1-启用 */
    private Integer status;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    private String remark;
}
