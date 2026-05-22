package com.krister.avatar.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

// Replaces Spring's default SimpleAsyncTaskExecutor (which spawns an unbounded new thread per task)
// with a bounded ThreadPoolTaskExecutor. AsyncConfigurer makes this the global default for @Async.
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    // Pool sizes and queue depth are env-var-backed so they can be tuned per environment without a rebuild
    @Value("${job.executor.core-pool-size:2}")
    private int corePoolSize;

    @Value("${job.executor.max-pool-size:4}")
    private int maxPoolSize;

    @Value("${job.executor.queue-capacity:100}")
    private int queueCapacity;

    @Bean(name = "jobExecutor")
    public ThreadPoolTaskExecutor jobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);   // threads kept alive even when idle
        executor.setMaxPoolSize(maxPoolSize);      // ceiling; new threads only after queue fills
        executor.setQueueCapacity(queueCapacity);  // backlog before max threads kick in
        // prefix makes these threads identifiable in logs and thread dumps
        executor.setThreadNamePrefix("job-worker-");
        // Captures the Micrometer Observation/trace context on the submitting thread and
        // restores it on the worker thread before the task runs. Without this, @Async breaks
        // the trace — processJob() would start a disconnected root span instead of a child
        // of the HTTP request span that triggered the job.
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        // when both queue and max threads are exhausted, throw so the controller can return 429
        executor.setRejectedExecutionHandler((r, e) -> {
            throw new RejectedExecutionException("Job queue is full — try again later");
        });
        executor.initialize();
        return executor;
    }

    // Wires jobExecutor as the target for all @Async method calls across the application
    @Override
    public Executor getAsyncExecutor() {
        return jobExecutor();
    }
}
