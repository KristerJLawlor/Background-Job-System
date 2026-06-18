package com.krister.avatar.api;

import com.krister.avatar.shared.RedisJobStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Manages a fixed pool of threads that each block on the Redis queue waiting for jobs.
// Running multiple threads (default: 2) allows jobs to be processed in parallel —
// while one thread is waiting for an image to download, another can be processing.
@Component
public class JobWorkerPool {

    private static final Logger log = LoggerFactory.getLogger(JobWorkerPool.class);

    private final RedisJobStore jobStore;
    private final JobProcessor processor;
    private final int threadCount;

    // volatile ensures that when stop() sets running=false on one thread, all worker threads
    // immediately see the updated value. Without volatile, the JVM could cache the value in
    // a CPU register and worker threads might never observe the change.
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
            // Thread.ofPlatform() is the Java 21 API for creating OS-level threads.
            // daemon(true) means these threads won't prevent the JVM from shutting down
            // if the main Spring context closes — the JVM exits even if they're still running.
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
                // dequeue blocks inside Redis (BRPOP) for up to 2 seconds, then returns null.
                // The 2-second timeout means each worker checks the `running` flag at least
                // every 2 seconds, so shutdown completes quickly after stop() is called.
                RedisJobStore.JobRequest req = jobStore.dequeue(Duration.ofSeconds(2));
                if (req == null) continue;
                processor.process(req.jobId(), req.url(), req.attempt());
            } catch (Exception e) {
                log.error("Worker loop error", e);
            }
        }
    }

    // @PreDestroy runs just before Spring shuts the bean down (e.g. on SIGTERM).
    // Setting running=false lets each worker thread finish its current job and then exit cleanly.
    @PreDestroy
    public void stop() {
        running = false;
        log.info("Job worker pool shutting down");
    }
}
