package com.krister.avatar.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

@Component
public class RedisJobStore {

    static final String QUEUE_KEY = "jobs:queue";
    private static final String RETRY_SET_KEY = "jobs:retry";
    private static final String STATUS_KEY = "job:%s:status";

    private final StringRedisTemplate stringRedis;
    private final ObjectMapper objectMapper;

    @Value("${job.result.ttl-minutes:60}")
    private long ttlMinutes;

    public RedisJobStore(StringRedisTemplate stringRedis, ObjectMapper objectMapper) {
        this.stringRedis = stringRedis;
        this.objectMapper = objectMapper;
    }

    public void setStatus(String jobId, JobStatus status) {
        stringRedis.opsForValue().set(
                STATUS_KEY.formatted(jobId), status.name(), Duration.ofMinutes(ttlMinutes));
    }

    public JobStatus getStatus(String jobId) {
        String val = stringRedis.opsForValue().get(STATUS_KEY.formatted(jobId));
        return val == null ? null : JobStatus.valueOf(val);
    }

    public void enqueue(String jobId, String url) {
        try {
            String payload = objectMapper.writeValueAsString(new JobRequest(jobId, url, 1));
            stringRedis.opsForList().leftPush(QUEUE_KEY, payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize job request", e);
        }
    }

    // Schedules a retry by adding the job to the sorted set with score = fire-at epoch second.
    // The RetryPromoter thread moves it back to jobs:queue once the delay has elapsed.
    public void scheduleRetry(String jobId, String url, int attempt, long delaySeconds) {
        try {
            String payload = objectMapper.writeValueAsString(new JobRequest(jobId, url, attempt));
            double fireAt = System.currentTimeMillis() / 1000.0 + delaySeconds;
            stringRedis.opsForZSet().add(RETRY_SET_KEY, payload, fireAt);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize retry request", e);
        }
    }

    // Moves all due retries (score <= now) from the retry sorted set into the work queue.
    // Returns the number of jobs promoted.
    public int promoteRetries() {
        double now = System.currentTimeMillis() / 1000.0;
        Set<String> due = stringRedis.opsForZSet().rangeByScore(RETRY_SET_KEY, 0, now);
        if (due == null || due.isEmpty()) return 0;
        for (String payload : due) {
            stringRedis.opsForList().leftPush(QUEUE_KEY, payload);
            stringRedis.opsForZSet().remove(RETRY_SET_KEY, payload);
        }
        return due.size();
    }

    // Blocking pop with timeout — returns null if no job arrives within the timeout window.
    public JobRequest dequeue(Duration timeout) {
        String payload = stringRedis.opsForList().rightPop(QUEUE_KEY, timeout);
        if (payload == null) return null;
        try {
            return objectMapper.readValue(payload, JobRequest.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize job request", e);
        }
    }

    public record JobRequest(String jobId, String url, int attempt) {}
}
