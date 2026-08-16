package com.ticketai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户（sys_user）
 */
@Data
@TableName("sys_user")
public class SysUserDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 密码 */
    private String password;

    private String nickname;

    private String phone;

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
