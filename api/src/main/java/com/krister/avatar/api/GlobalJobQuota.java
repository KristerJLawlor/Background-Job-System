package com.krister.avatar.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

// Enforces a global daily cap on job submissions across all users combined.
// This is the application-level cost protection: even if someone rotates IPs to
// bypass the per-IP rate limiter, the shared counter stops them here.
@Component
class GlobalJobQuota {

    private static final Logger log = LoggerFactory.getLogger(GlobalJobQuota.class);

    private final StringRedisTemplate redis;

    @Value("${job.global.daily-limit}")
    private int dailyLimit;

    GlobalJobQuota(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // Returns true if a slot is available (and consumes it), false if today's quota is exhausted.
    boolean tryConsume() {
        try {
            // The key includes today's UTC date, so it resets automatically at midnight
            // with no scheduled task — a new key is just created the following day.
            String key = "global:jobs:daily:" + LocalDate.now(ZoneOffset.UTC);

            // INCR is atomic in Redis — even with multiple API processes running simultaneously,
            // each increment is guaranteed to see the correct previous value. No race condition.
            Long count = redis.opsForValue().increment(key);

            // Set the TTL only on the very first increment (count == 1) to avoid resetting the
            // expiry on every request. 2 days ensures the prior day's key is gone before it could
            // wrap around to the same date key again.
            if (count == 1) {
                redis.expire(key, Duration.ofDays(2));
            }
            return count <= dailyLimit;
        } catch (Exception e) {
            // Fail open: if Redis is temporarily unavailable, allow the request through.
            // Blocking all submissions because of a quota outage would be worse than
            // briefly exceeding the daily limit during a Redis hiccup.
            log.warn("GlobalJobQuota Redis error — failing open: {}", e.getMessage());
            return true;
        }
    }
}
