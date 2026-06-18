package com.krister.avatar.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Owns all Redis interactions for the job pipeline. Uses four different Redis data structures,
// each chosen for its access pattern:
//
//   jobs:queue       → List   (LPUSH/BRPOP — FIFO work queue)
//   job:{id}:status  → String (simple key-value with TTL auto-expiry)
//   jobs:retry       → Sorted Set (score = fire-at epoch second, enables time-ordered scheduling)
//   jobs:dlq         → Hash   (jobId field → JSON value, easy keyed lookup)
//
// Java objects are serialized to JSON strings because Redis only stores strings natively.
@Component
public class RedisJobStore {

    public static final String QUEUE_KEY = "jobs:queue";
    private static final String RETRY_SET_KEY = "jobs:retry";
    private static final String STATUS_KEY = "job:%s:status";
    private static final String DLQ_KEY = "jobs:dlq";

    private final StringRedisTemplate stringRedis;
    // ObjectMapper (Jackson) converts Java objects to/from JSON strings for storage in Redis.
    private final ObjectMapper objectMapper;

    @Value("${job.result.ttl-minutes:60}")
    private long ttlMinutes;

    public RedisJobStore(StringRedisTemplate stringRedis, ObjectMapper objectMapper) {
        this.stringRedis = stringRedis;
        this.objectMapper = objectMapper;
    }

    // Stores a plain string with a TTL. Redis automatically deletes the key when the TTL
    // expires — no cron job or cleanup code needed. Clients polling for status will receive
    // null (→ 404) once the TTL elapses.
    public void setStatus(String jobId, JobStatus status) {
        stringRedis.opsForValue().set(
                STATUS_KEY.formatted(jobId), status.name(), Duration.ofMinutes(ttlMinutes));
    }

    public JobStatus getStatus(String jobId) {
        String val = stringRedis.opsForValue().get(STATUS_KEY.formatted(jobId));
        return val == null ? null : JobStatus.valueOf(val);
    }

    // LPUSH pushes to the left (head) of the list; BRPOP pops from the right (tail).
    // This left-push / right-pop pattern makes the List behave as a FIFO queue so jobs
    // are processed in the order they were submitted.
    public void enqueue(String jobId, String url) {
        try {
            String payload = objectMapper.writeValueAsString(new JobRequest(jobId, url, 1));
            stringRedis.opsForList().leftPush(QUEUE_KEY, payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize job request", e);
        }
    }

    // Schedules a retry by adding the job to the sorted set with score = fire-at epoch second.
    // Redis Sorted Sets keep members ordered by score — querying "score <= now" efficiently
    // finds every job whose delay has elapsed without scanning the full set.
    // The RetryPromoter thread moves due entries back to jobs:queue every 5 seconds.
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

    // BRPOP (blocking right-pop) makes the worker thread sleep inside Redis until a job
    // arrives, rather than busy-polling in a tight loop. The timeout parameter caps how
    // long the thread blocks — after which it returns null so the caller can check
    // whether it should shut down gracefully.
    public JobRequest dequeue(Duration timeout) {
        String payload = stringRedis.opsForList().rightPop(QUEUE_KEY, timeout);
        if (payload == null) return null;
        try {
            return objectMapper.readValue(payload, JobRequest.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize job request", e);
        }
    }

    // Dead Letter Queue: jobs that have exhausted all retry attempts land here.
    // A Hash (field → value map) is used so the admin API can look up or delete a
    // specific job by ID in O(1) without scanning every failed entry.
    public void pushToDlq(String jobId, String url, int attempts, String error) {
        try {
            DlqEntry entry = new DlqEntry(jobId, url, attempts, Instant.now().getEpochSecond(), error);
            stringRedis.opsForHash().put(DLQ_KEY, jobId, objectMapper.writeValueAsString(entry));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize DLQ entry", e);
        }
    }

    public List<DlqEntry> listDlq() {
        Map<Object, Object> entries = stringRedis.opsForHash().entries(DLQ_KEY);
        return entries.values().stream()
                .map(v -> {
                    try {
                        return objectMapper.readValue((String) v, DlqEntry.class);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException("Failed to deserialize DLQ entry", e);
                    }
                })
                .sorted(Comparator.comparingLong(DlqEntry::failedAt).reversed())
                .toList();
    }

    // Requeuing resets the attempt counter to 1 so the job gets a fresh set of retries.
    public boolean requeueFromDlq(String jobId) {
        String raw = (String) stringRedis.opsForHash().get(DLQ_KEY, jobId);
        if (raw == null) return false;
        try {
            DlqEntry entry = objectMapper.readValue(raw, DlqEntry.class);
            stringRedis.opsForHash().delete(DLQ_KEY, jobId);
            String payload = objectMapper.writeValueAsString(new JobRequest(jobId, entry.url(), 1));
            stringRedis.opsForList().leftPush(QUEUE_KEY, payload);
            setStatus(jobId, JobStatus.PENDING);
            return true;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to requeue from DLQ", e);
        }
    }

    public boolean removeFromDlq(String jobId) {
        Long deleted = stringRedis.opsForHash().delete(DLQ_KEY, jobId);
        return deleted != null && deleted > 0;
    }

    // Java records are immutable data carriers — the compiler auto-generates the constructor,
    // getters (jobId(), url(), etc.), equals, hashCode, and toString. Jackson serializes them
    // to/from JSON without any extra annotations.
    public record JobRequest(String jobId, String url, int attempt) {}

    public record DlqEntry(String jobId, String url, int attempts, long failedAt, String error) {}
}
