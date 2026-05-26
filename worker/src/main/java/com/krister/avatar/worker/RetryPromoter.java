package com.krister.avatar.worker;

import com.krister.avatar.shared.RedisJobStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
