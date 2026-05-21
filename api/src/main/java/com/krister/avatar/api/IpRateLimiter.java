package com.krister.avatar.api;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Component
class IpRateLimiter {

    // One Bucket per client IP, created lazily on first request.
    // Each entry is ~100 bytes; the map is bounded in practice by the number of
    // distinct IPs that have ever submitted a job — acceptable for a focused service.
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Value("${job.rate-limit.requests-per-minute:10}")
    private int requestsPerMinute;

    // Returns true if the request is within the caller's rate limit, false if throttled.
    boolean tryConsume(HttpServletRequest request) {
        String ip = resolveClientIp(request);
        Bucket bucket = buckets.computeIfAbsent(ip, k -> newBucket());
        return bucket.tryConsume(1);
    }

    // Greedy refill distributes tokens continuously across the window, so a client
    // with a 10 req/min limit gets one token every 6 seconds rather than 10 tokens
    // dropping all at once on the minute boundary. This prevents burst-at-reset abuse.
    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(requestsPerMinute)
                .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    // Prefers X-Forwarded-For so rate limiting works correctly behind AWS ALB —
    // without it, getRemoteAddr() returns the load balancer IP and all clients share
    // one bucket. Only the first (leftmost) IP is used, which is the original client.
    // Caveat: X-Forwarded-For can be spoofed by clients if the ALB is misconfigured to
    // forward existing headers rather than overwrite them. Properly configured ALBs
    // always set this header themselves, making spoofing impossible.
    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
