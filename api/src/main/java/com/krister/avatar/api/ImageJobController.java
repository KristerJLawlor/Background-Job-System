package com.krister.avatar.api;

import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class ImageJobController {

    private static final Logger log = LoggerFactory.getLogger(ImageJobController.class);

    private final ImageJobService jobService;
    private final IpRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;

    public ImageJobController(ImageJobService jobService, IpRateLimiter rateLimiter,
                              MeterRegistry meterRegistry) {
        this.jobService = jobService;
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
    }

    // Submit job
    @PostMapping
    public ResponseEntity<?> submitJob(@RequestParam String url, HttpServletRequest request) {
        // Rate limit checked first — before URL validation — so throttled requests never
        // trigger DNS resolution or any downstream work.
        if (!rateLimiter.tryConsume(request)) {
            // "reason" tag lets dashboards break rejections down by cause without separate metrics
            meterRegistry.counter("jobs.rejected", "reason", "rate_limited").increment();
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Rate limit exceeded — try again later"));
        }
        try {
            UrlValidator.validate(url);
            String jobId = jobService.createJob(url);
            meterRegistry.counter("jobs.submitted").increment();
            return ResponseEntity.ok(Map.of("jobId", jobId));
        } catch (IllegalArgumentException e) {
            meterRegistry.counter("jobs.rejected", "reason", "invalid_url").increment();
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Check status
    @GetMapping("/{jobId}")
    public ResponseEntity<String> getStatus(@PathVariable String jobId) {
        JobStatus status = jobService.getStatus(jobId);
        log.debug("Status check jobId={} status={}", jobId, status);

        if (status == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(status.toString());
    }

    // Get result
    @GetMapping("/{jobId}/result")
    public ResponseEntity<byte[]> getResult(@PathVariable String jobId) {
        if (jobService.getStatus(jobId) != JobStatus.COMPLETED) {
            return ResponseEntity.badRequest().build();
        }

        // claimResult atomically removes the PNG bytes from Redis, freeing memory immediately.
        // Returns null if the result was already claimed or expired.
        byte[] pngBytes = jobService.claimResult(jobId);
        if (pngBytes == null) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(pngBytes);
    }

}
