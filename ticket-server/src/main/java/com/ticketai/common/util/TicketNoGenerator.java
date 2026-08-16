package com.ticketai.common.util;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 工单编号生成器（DEV_DOC §4.2.4）：
 * ticket_no = "T" + yyyyMMdd + 6位序列，Redis INCR 当日自增。
 * 注意：不使用 spring-data-redis 的 expire(Duration) 方法（Redis 3.0 环境下
 * DefaultedRedisConnection 默认方法互调会 StackOverflow），改用 Lua 一步完成。
 */
@Component
@RequiredArgsConstructor
public class TicketNoGenerator {

    private static final String KEY_PREFIX = "ticket:no:";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Lua：INCR 并当日首次时设置 2 天过期（防 key 堆积），原子执行 */
    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local n = redis.call('incr', KEYS[1]); if n == 1 then redis.call('expire', KEYS[1], 172800) end; return n",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public String next() {
        String day = LocalDate.now().format(DAY);
        Long seq = stringRedisTemplate.execute(INCR_WITH_TTL, List.of(KEY_PREFIX + day));
        if (seq == null || seq > 999_999L) {
            throw new IllegalStateException("工单编号序列超限: " + day);
        }
        return "T" + day + String.format("%06d", seq);
    }
}
