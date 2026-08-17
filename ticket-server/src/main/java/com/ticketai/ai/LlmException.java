package com.ticketai.ai;

import lombok.Getter;

/**
 * LLM 调用异常。调用方按原因分类降级。
 */
@Getter
public class LlmException extends RuntimeException {

    public static final String NO_KEY = "NO_KEY";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String SERVER_ERROR = "SERVER_ERROR";
    public static final String PARSE_ERROR = "PARSE_ERROR";

    private final String reason;

    public LlmException(String message, String reason) {
        super(message);
        this.reason = reason;
    }
}
