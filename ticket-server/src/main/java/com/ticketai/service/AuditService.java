package com.ticketai.service;

/**
 * 审计服务（DEV_DOC §6.x / docs P1-9）：敏感操作必须落 audit_log。
 * 实现从线程上下文取当前用户与客户端 IP；审计失败不影响业务主链路。
 */
public interface AuditService {

    /**
     * 记录一条审计日志。
     *
     * @param action     动作码，如 TICKET_ASSIGN / LOGIN_FAILED（见 DEV_DOC）
     * @param targetType 对象类型，如 TICKET / LOGIN
     * @param targetId   对象 ID（无则 null）
     * @param username   操作者登录名（null 则回退为当前登录用户）
     * @param detail     详情 JSON（可 null）
     */
    void record(String action, String targetType, Long targetId, String username, String detail);
}