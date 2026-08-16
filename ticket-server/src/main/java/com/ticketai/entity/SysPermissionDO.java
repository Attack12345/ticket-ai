package com.ticketai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限（sys_permission）
 */
@Data
@TableName("sys_permission")
public class SysPermissionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 权限码，如 ticket:claim */
    private String code;

    private String name;

    /** MENU / BUTTON */
    private String type;

    private Long parentId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
