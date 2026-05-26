package com.krister.avatar.api;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.krister.avatar.core.DiscordImageResizer;

@Service
public class ImageJobService {

    private static final Logger log = LoggerFactory.getLogger(ImageJobService.class);

    private final RedisJobStore jobStore;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    @Value("${job.retry.max-attempts:3}")
    private int maxAttempts;

    @Value("${job.retry.base-delay-seconds:10}")
    private long baseDelaySeconds;

    // Tracks in-flight jobs on this instance; Micrometer samples it directly as a gauge.
    private final AtomicInteger activeJobs = new AtomicInteger(0);

    public ImageJobService(RedisJobStore jobStore, MeterRegistry meterRegistry, Tracer tracer) {
        this.jobStore = jobStore;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
        Gauge.builder("jobs.active", activeJobs, AtomicInteger::get)
                .description("Jobs currently being processed by the worker pool")
                .register(meterRegistry);
    }

    public String createJob(String url) {
        String jobId = UUID.randomUUID().toString();
        jobStore.setStatus(jobId, JobStatus.PENDING);
        jobStore.enqueue(jobId, url);
        log.info("Job created jobId={}", jobId);
        return jobId;
    }

    public void processJob(String jobId, String url, int attempt) {
        MDC.put("jobId", jobId);
        MDC.put("attempt", String.valueOf(attempt));
        Timer.Sample sample = Timer.start(meterRegistry);
        activeJobs.incrementAndGet();

        Span span = tracer.nextSpan().name("job.process").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag("job.id", jobId);
            span.tag("job.attempt", String.valueOf(attempt));

            try {
                jobStore.setStatus(jobId, JobStatus.PROCESSING);
                log.info("Job processing started");

                BufferedImage resized = DiscordImageResizer.downloadAndResize(url);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(resized, "png", baos);
                jobStore.storeResult(jobId, baos.toByteArray());
                jobStore.setStatus(jobId, JobStatus.COMPLETED);

                log.info("Job completed");
                span.tag("job.outcome", "completed");
                sample.stop(meterRegistry.timer("jobs.processing.duration", "status", "completed"));

            } catch (Exception e) {
                span.error(e);

                if (attempt < maxAttempts) {
                    // Exponential backoff: 10s, 20s, 40s for attempts 1, 2, 3 (with defaults)
                    long delaySeconds = baseDelaySeconds * (1L << (attempt - 1));
                    jobStore.scheduleRetry(jobId, url, attempt + 1, delaySeconds);
                    jobStore.setStatus(jobId, JobStatus.PENDING);
                    meterRegistry.counter("jobs.retried").increment();
                    span.tag("job.outcome", "retrying");
                    log.warn("Job failed, scheduling retry nextAttempt={} delaySeconds={}", attempt + 1, delaySeconds, e);
                } else {
                    jobStore.setStatus(jobId, JobStatus.FAILED);
                    span.tag("job.outcome", "failed");
                    log.error("Job failed after max attempts attempt={}", attempt, e);
                }

                sample.stop(meterRegistry.timer("jobs.processing.duration", "status", "failed"));
            }
        } finally {
            span.end();
            activeJobs.decrementAndGet();
            MDC.clear();
        }
    }

    public byte[] claimResult(String jobId) {
        return jobStore.claimResult(jobId);
    }

    public JobStatus getStatus(String jobId) {
        return jobStore.getStatus(jobId);
    }
}
