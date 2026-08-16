package com.ticketai.common.exception;

import lombok.Getter;

/**
 * 错误码枚举（DEV_DOC §6.1，禁止新增枚举值之外的自定义 code）
 */
@Getter
public enum ErrorCode {

    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未认证或登录已过期"),
    FORBIDDEN(403, "无权限执行此操作"),
    NOT_FOUND(404, "资源不存在"),
    CONCURRENT_MODIFY(409, "数据已被其他人修改，请刷新后重试"),
    ILLEGAL_TRANSITION(409, "非法的工单状态流转"),
    DUPLICATE_MESSAGE(409, "渠道消息重复"),
    SYSTEM_ERROR(500, "系统异常，请稍后重试"),
    AI_UNAVAILABLE(503, "AI 服务暂不可用");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
