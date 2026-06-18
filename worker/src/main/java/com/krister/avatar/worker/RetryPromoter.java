package com.krister.avatar.worker;

import com.krister.avatar.shared.RedisJobStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Polls the Redis retry sorted set every 5 seconds and moves any jobs whose delay
// has elapsed back into the main work queue. This decouples retry scheduling from
// job processing — the worker threads don't need to know anything about retries.
//
// An alternative would be Spring's @Scheduled annotation, but a plain thread is simpler
// here since we need manual lifecycle control (stop() on shutdown) anyway.
@Component
public class RetryPromoter {

    private static final Logger log = LoggerFactory.getLogger(RetryPromoter.class);
    private static final long POLL_INTERVAL_MS = 5_000;

    private final RedisJobStore jobStore;
    private volatile boolean running = true;

    public RetryPromoter(RedisJobStore jobStore) {
        this.jobStore = jobStore;
    }

    @PostConstruct
    public void start() {
        Thread.ofPlatform()
                .name("retry-promoter")
                .daemon(true)
                .start(this::loop);
        log.info("Retry promoter started pollIntervalMs={}", POLL_INTERVAL_MS);
    }

    private void loop() {
        while (running) {
            try {
                int promoted = jobStore.promoteRetries();
                if (promoted > 0) log.info("Promoted retry jobs count={}", promoted);
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                // Re-interrupt the thread so callers higher up the stack can also observe it.
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Retry promoter error", e);
            }
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        log.info("Retry promoter shutting down");
    }
}
