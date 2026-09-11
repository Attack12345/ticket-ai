package com.ticketai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置（P1-5：LLM 慢/挂不能拖垮核心任务，按特征拆两个隔离池）：
 * aiExecutor（LLM 调用：分类/embedding/suggest）与 coreExecutor（自动分派/ES 索引写入等核心任务），
 * 各自独立排队，LLM 池打满只影响 AI 增强链路，不影响 SLA 关键路径。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** LLM 调用池（@Async("aiExecutor")）：分类/embedding/suggest 等 AI 增强任务 */
    @Bean("aiExecutor")
    public Executor aiExecutor() {
        return createExecutor("ai-executor-");
    }

    /** 核心任务池（@Async("coreExecutor")）：自动分派、ES 索引写入等，不依赖 LLM 健康 */
    @Bean("coreExecutor")
    public Executor coreExecutor() {
        return createExecutor("core-executor-");
    }

    private ThreadPoolTaskExecutor createExecutor(String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
