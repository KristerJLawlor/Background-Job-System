package com.krister.avatar.api;

import com.krister.avatar.shared.JobStatus;
import com.krister.avatar.shared.RedisJobStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class RedisJobStoreIntegrationTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired RedisJobStore jobStore;
    @Autowired StringRedisTemplate stringRedis;

    @BeforeEach
    void flushRedis() {
        stringRedis.execute((RedisCallback<Void>) conn -> {
            conn.serverCommands().flushAll();
            return null;
        });
    }

    // --- status ---

    @Test
    void setAndGetStatus_roundtrip() {
        jobStore.setStatus("job-1", JobStatus.PENDING);
        assertThat(jobStore.getStatus("job-1")).isEqualTo(JobStatus.PENDING);

        jobStore.setStatus("job-1", JobStatus.COMPLETED);
        assertThat(jobStore.getStatus("job-1")).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void getStatus_unknownJob_returnsNull() {
        assertThat(jobStore.getStatus("nonexistent")).isNull();
    }

    // --- queue ---

    @Test
    void enqueueAndDequeue_roundtrip() {
        jobStore.enqueue("job-1", "https://1.1.1.1/img.png");

        RedisJobStore.JobRequest req = jobStore.dequeue(Duration.ofMillis(500));

        assertThat(req).isNotNull();
        assertThat(req.jobId()).isEqualTo("job-1");
        assertThat(req.url()).isEqualTo("https://1.1.1.1/img.png");
        assertThat(req.attempt()).isEqualTo(1);
    }

    @Test
    void dequeue_emptyQueue_returnsNull() {
        assertThat(jobStore.dequeue(Duration.ofMillis(100))).isNull();
    }

    // --- retry set ---

    @Test
    void scheduleRetry_doesNotAppearInMainQueueImmediately() {
        jobStore.scheduleRetry("job-1", "https://1.1.1.1/img.png", 2, 3600L);

        Long queueSize = stringRedis.opsForList().size(RedisJobStore.QUEUE_KEY);
        assertThat(queueSize).isEqualTo(0);
    }

    @Test
    void promoteRetries_movesOverdueJobsToQueue() {
        // negative delay → score is in the past → immediately due
        jobStore.scheduleRetry("job-1", "https://1.1.1.1/img.png", 2, -10L);

        int promoted = jobStore.promoteRetries();
        assertThat(promoted).isEqualTo(1);

        RedisJobStore.JobRequest req = jobStore.dequeue(Duration.ofMillis(500));
        assertThat(req).isNotNull();
        assertThat(req.jobId()).isEqualTo("job-1");
        assertThat(req.attempt()).isEqualTo(2);
    }

    @Test
    void promoteRetries_doesNotMoveFutureJobs() {
        jobStore.scheduleRetry("job-1", "https://1.1.1.1/img.png", 2, 3600L);

        int promoted = jobStore.promoteRetries();
        assertThat(promoted).isEqualTo(0);
    }

    @Test
    void promoteRetries_onlyMovesOverdueSubset() {
        jobStore.scheduleRetry("job-due",    "https://1.1.1.1/img.png", 2, -10L);  // past
        jobStore.scheduleRetry("job-future", "https://2.2.2.2/img.png", 2, 3600L); // future

        int promoted = jobStore.promoteRetries();
        assertThat(promoted).isEqualTo(1);

        RedisJobStore.JobRequest req = jobStore.dequeue(Duration.ofMillis(500));
        assertThat(req.jobId()).isEqualTo("job-due");
    }
}
