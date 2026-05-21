package com.krister.avatar.api;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;

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

    public ImageJobController(ImageJobService jobService, IpRateLimiter rateLimiter) {
        this.jobService = jobService;
        this.rateLimiter = rateLimiter;
    }

    // Submit job
    @PostMapping
    public ResponseEntity<?> submitJob(@RequestParam String url, HttpServletRequest request) {
        // Rate limit checked first — before URL validation — so throttled requests never
        // trigger DNS resolution or any downstream work.
        if (!rateLimiter.tryConsume(request)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Rate limit exceeded — try again later"));
        }
        try {
            UrlValidator.validate(url);
            String jobId = jobService.createJob(url);
            return ResponseEntity.ok(Map.of("jobId", jobId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RejectedExecutionException e) {
            // Thrown by AsyncConfig when the executor queue is full; surface as 429 rather than 500
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Job queue is full — try again later"));
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

        // claimResult removes the BufferedImage from the map atomically, freeing memory
        // immediately. Returns null if the result was already claimed or evicted.
        BufferedImage image = jobService.claimResult(jobId);
        if (image == null) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);

            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(baos.toByteArray());

        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/test")
public String test() {
    return "CONTROLLER IS ACTIVE";
}

}
