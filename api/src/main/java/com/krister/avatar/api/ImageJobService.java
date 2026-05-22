package com.krister.avatar.api;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.krister.avatar.core.DiscordImageResizer;

@Service
public class ImageJobService {

    private static final Logger log = LoggerFactory.getLogger(ImageJobService.class);

    private final Map<String, JobStatus> jobStatuses = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> results = new ConcurrentHashMap<>();
    // tracks creation time for each job so the eviction sweep knows what to expire
    private final Map<String, Instant> createdAt = new ConcurrentHashMap<>();

    // AtomicInteger used as both the gauge backing value and the live counter —
    // Micrometer reads it directly, so the gauge always reflects the real-time value.
    private final AtomicInteger activeJobs = new AtomicInteger(0);

    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    @Value("${job.result.ttl-minutes:60}")
    private long ttlMinutes;

    public ImageJobService(MeterRegistry meterRegistry, Tracer tracer) {
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
        // Gauge is registered once at construction; Micrometer samples activeJobs on each scrape.
        Gauge.builder("jobs.active", activeJobs, AtomicInteger::get)
                .description("Jobs currently being processed by the worker pool")
                .register(meterRegistry);
    }

    public String createJob(String url) {
        String jobId = UUID.randomUUID().toString();
        jobStatuses.put(jobId, JobStatus.PENDING);
        createdAt.put(jobId, Instant.now());
        log.info("Job created jobId={}", jobId);
        processJob(jobId, url);
        return jobId;
    }

    @Async
    public void processJob(String jobId, String url) {
        // MDC makes jobId a top-level field on every log line emitted during this job,
        // enabling "filter jobId = '...'" queries in CloudWatch Insights / Datadog.
        MDC.put("jobId", jobId);
        Timer.Sample sample = Timer.start(meterRegistry);
        activeJobs.incrementAndGet();

        // nextSpan() creates a child of the span restored by ContextPropagatingTaskDecorator
        // (the POST /api/jobs request span). withSpan() sets it as the active span on this
        // thread, so traceId/spanId flow into MDC and appear in every log line below.
        Span span = tracer.nextSpan().name("job.process").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag("job.id", jobId);

            try {
                jobStatuses.put(jobId, JobStatus.PROCESSING);
                log.info("Job processing started");

                BufferedImage resized = DiscordImageResizer.downloadAndResize(url);

                results.put(jobId, resized);
                jobStatuses.put(jobId, JobStatus.COMPLETED);
                log.info("Job completed");
                span.tag("job.outcome", "completed");
                sample.stop(meterRegistry.timer("jobs.processing.duration", "status", "completed"));

            } catch (Exception e) {
                span.error(e);
                span.tag("job.outcome", "failed");
                jobStatuses.put(jobId, JobStatus.FAILED);
                log.error("Job failed", e);
                sample.stop(meterRegistry.timer("jobs.processing.duration", "status", "failed"));
            }
        } finally {
            span.end();
            activeJobs.decrementAndGet();
            MDC.clear();
        }
    }

    // Removes and returns the image in one atomic step so the BufferedImage is freed
    // immediately after the client claims it, rather than lingering until the next sweep.
    // Returns null if the result was already claimed or has not completed yet.
    public BufferedImage claimResult(String jobId) {
        return results.remove(jobId);
    }

    // Periodic sweep that removes all three map entries for jobs older than ttlMinutes.
    // Catches jobs whose results were never retrieved (failed, abandoned, or unclaimed).
    @Scheduled(fixedDelayString = "${job.result.eviction-interval-ms:60000}")
    public void evictExpiredJobs() {
        Instant cutoff = Instant.now().minus(ttlMinutes, ChronoUnit.MINUTES);
        AtomicInteger evicted = new AtomicInteger();
        createdAt.entrySet().removeIf(entry -> {
            if (entry.getValue().isBefore(cutoff)) {
                String jobId = entry.getKey();
                jobStatuses.remove(jobId);
                results.remove(jobId);
                evicted.incrementAndGet();
                return true;
            }
            return false;
        });
        if (evicted.get() > 0) {
            meterRegistry.counter("jobs.evicted").increment(evicted.get());
            log.info("Evicted expired jobs count={}", evicted.get());
        }
    }

    public JobStatus getStatus(String jobId) {
        return jobStatuses.get(jobId);
    }

}
