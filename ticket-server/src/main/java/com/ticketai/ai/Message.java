package com.ticketai.ai;

/**
 * 对话消息（OpenAI 兼容协议）。
 */
public record Message(String role, String content) {

    public static Message system(String content) {
        return new Message("system", content);
    }

    public static Message user(String content) {
        return new Message("user", content);
    }
}
