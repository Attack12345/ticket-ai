package com.ticketai.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 手动熔断记录器（P1-5，不引 Resilience4j，避免外部依赖与版本风险）。
 * 语义：窗口内"连续"失败 N 次 → 熔断 T 秒；熔断期内调用方立即拿到 CIRCUIT_OPEN 异常，
 * 走既有降级链路（规则/兜底/返回 null）。
 * 线程安全：全部状态读写均置于 synchronized 内（LLM 调用低频，锁开销可忽略）；
 * 失败记录用环形窗口，环大小=阈值，达到第 N 次即熔断，不会丢失窗口内失败记录。
 */
@Slf4j
@Component
public class LlmCircuitBreaker {

    /** 环形失败时间窗口（只记录失败时刻；环大小=阈值，第 N 次即熔断） */
    private final long[] failureTimes;
    private int ringIndex;

    private final int failureThreshold; // 失败 N 次熔断
    private final long windowMs;        // 失败窗口时长，超出窗口的失败不计
    private final long openMs;          // 熔断保持时长

    private boolean open;
    private long openUntilMs;

    public LlmCircuitBreaker(
            @Value("${app.llm.circuit.failure-threshold:5}") int failureThreshold,
            @Value("${app.llm.circuit.window-ms:10000}") long windowMs,
            @Value("${app.llm.circuit.open-ms:30000}") long openMs) {
        if (failureThreshold <= 0 || windowMs <= 0 || openMs <= 0) {
            throw new IllegalArgumentException("LLM 熔断参数必须为正数");
        }
        this.failureThreshold = failureThreshold;
        this.windowMs = windowMs;
        this.openMs = openMs;
        this.failureTimes = new long[failureThreshold];
    }

    /**
     * 是否放行本次调用。熔断打开返回 false；达到 openMs 后放行一个探测请求（半开）。
     */
    public synchronized boolean tryAcquire() {
        if (open) {
            if (System.currentTimeMillis() >= openUntilMs) {
                open = false; // 半开：放行探测，成败由 recordSuccess/recordFailure 决定
                return true;
            }
            return false;
        }
        return true;
    }

    /** 记录一次成功：复位失败窗口并关断熔断。 */
    public synchronized void recordSuccess() {
        open = false;
        for (int i = 0; i < failureTimes.length; i++) {
            failureTimes[i] = 0;
        }
        ringIndex = 0;
    }

    /** 记录一次失败：窗口内失败数达到阈值 → 熔断 openMs。 */
    public synchronized void recordFailure() {
        long now = System.currentTimeMillis();
        failureTimes[ringIndex] = now;
        ringIndex = (ringIndex + 1) % failureTimes.length;
        if (!open && countInWindow(now) >= failureThreshold) {
            open = true;
            openUntilMs = now + openMs;
            log.warn("LLM 熔断打开: {}ms 窗口内失败 {} 次，{}ms 内快速降级", windowMs, failureThreshold, openMs);
        }
    }

    private int countInWindow(long now) {
        long windowStart = now - windowMs;
        int count = 0;
        for (long t : failureTimes) {
            if (t >= windowStart) {
                count++;
            }
        }
        return count;
    }
}