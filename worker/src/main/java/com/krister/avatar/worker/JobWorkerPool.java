package com.krister.avatar.worker;

import com.krister.avatar.shared.RedisJobStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class JobWorkerPool {

    private static final Logger log = LoggerFactory.getLogger(JobWorkerPool.class);

    private final RedisJobStore jobStore;
    private final JobProcessor processor;
    private final int threadCount;
    private volatile boolean running = true;

    public JobWorkerPool(RedisJobStore jobStore, JobProcessor processor,
                         @Value("${job.worker.thread-count:2}") int threadCount) {
        this.jobStore = jobStore;
        this.processor = processor;
        this.threadCount = threadCount;
    }

    @PostConstruct
    public void start() {
        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            Thread.ofPlatform()
                    .name("job-worker-" + idx)
                    .daemon(true)
                    .start(this::workerLoop);
        }
        log.info("Job worker pool started threads={}", threadCount);
    }

    private void workerLoop() {
        while (running) {
            try {
                RedisJobStore.JobRequest req = jobStore.dequeue(Duration.ofSeconds(2));
                if (req == null) continue;
                processor.process(req.jobId(), req.url(), req.attempt());
            } catch (Exception e) {
                log.error("Worker loop error", e);
            }
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        log.info("Job worker pool shutting down");
    }
}
