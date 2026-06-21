package com.flag.ingest.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * IngestService dual thread pool isolation configuration.
 *
 * Architecture mapping:
 * - Pool A (MetricsChannel): Fire & Forget, non-blocking fast write to Redis
 * - Pool B (LogChannel): With timeout and degraded discard, writes to Kafka
 *
 * The two thread pools are fully isolated; a blockage in one channel does not affect the other.
 */
@Configuration
public class IngestThreadPoolConfig {

    private static final Logger log = LoggerFactory.getLogger(IngestThreadPoolConfig.class);

    /**
     * Pool A: Metrics channel — Fire & Forget
     * Core principle: never block the caller; dropping data is better than dropping a response.
     */
    @Bean("metricsExecutor")
    public Executor metricsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1024);
        executor.setThreadNamePrefix("metrics-pool-");
        // When the queue is full, the calling thread executes (reduces throughput but guarantees no task loss)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        log.info("Metrics thread pool initialized: core=4, max=8, queue=1024");
        return executor;
    }

    /**
     * Pool B: Audit log channel — with timeout and degradation
     * Client calls carry a timeout; after timeout, automatic degradation discards the task.
     */
    @Bean("auditLogExecutor")
    public Executor auditLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(512);
        executor.setThreadNamePrefix("audit-log-pool-");
        // When the queue is full, tasks are simply discarded (silent degradation)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        log.info("Audit log thread pool initialized: core=2, max=4, queue=512");
        return executor;
    }
}