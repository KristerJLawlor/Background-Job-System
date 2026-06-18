package com.krister.avatar.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

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
    // Fails open on Redis errors so an outage doesn't block all submissions.
    boolean tryConsume() {
        try {
            String key = "global:jobs:daily:" + LocalDate.now(ZoneOffset.UTC);
            Long count = redis.opsForValue().increment(key);
            if (count == 1) {
                redis.expire(key, Duration.ofDays(2));
            }
            return count <= dailyLimit;
        } catch (Exception e) {
            log.warn("GlobalJobQuota Redis error — failing open: {}", e.getMessage());
            return true;
        }
    }
}
