package com.ticketai.security;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 当前登录用户信息（由 JWT 解析构造）。
 */
@Data
@AllArgsConstructor
public class LoginUser {

    private Long userId;

    private String username;

    /** 坐席ID（M2 接入 agent 表后填充，暂为 null） */
    private Long agentId;

    /** 权限码列表，如 ticket:claim */
    private List<String> permissions;
}
