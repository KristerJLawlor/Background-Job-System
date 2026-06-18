package com.krister.avatar.api;

import com.krister.avatar.core.AnimatedGifProcessor;
import com.krister.avatar.core.DiscordImageResizer;
import com.krister.avatar.shared.JobStatus;
import com.krister.avatar.shared.ProcessingResult;
import com.krister.avatar.shared.RedisJobStore;
import com.krister.avatar.shared.S3ResultStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class JobProcessor {

    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    private final RedisJobStore jobStore;
    private final S3ResultStore s3ResultStore;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    @Value("${job.retry.max-attempts:3}")
    private int maxAttempts;

    @Value("${job.retry.base-delay-seconds:10}")
    private long baseDelaySeconds;

    // AtomicInteger is a thread-safe integer — increment/decrement are guaranteed to be
    // seen correctly by all threads without synchronization blocks.
    private final AtomicInteger activeJobs = new AtomicInteger(0);

    public JobProcessor(RedisJobStore jobStore, S3ResultStore s3ResultStore,
                        MeterRegistry meterRegistry, Tracer tracer) {
        this.jobStore = jobStore;
        this.s3ResultStore = s3ResultStore;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
        // Gauge is a metric that reflects a current value (like a fuel gauge), as opposed
        // to a Counter (ever-increasing) or Timer (duration histogram). This makes the
        // "currently active jobs" number visible in Grafana in real time.
        Gauge.builder("jobs.active", activeJobs, AtomicInteger::get)
                .description("Jobs currently being processed by the worker pool")
                .register(meterRegistry);
    }

    public void process(String jobId, String url, int attempt) {
        // MDC (Mapped Diagnostic Context) attaches key-value pairs to the current thread's
        // log context. Every log.info/warn/error call below will automatically include
        // jobId and attempt in the log output, making it easy to trace a job across log lines.
        MDC.put("jobId", jobId);
        MDC.put("attempt", String.valueOf(attempt));
        // Timer.Sample captures the start timestamp; .stop() records the elapsed duration.
        Timer.Sample sample = Timer.start(meterRegistry);
        activeJobs.incrementAndGet();

        // Distributed tracing: a Span represents a unit of work. This span covers the entire
        // job processing step. In Jaeger, it appears as a child of the /api/jobs HTTP span
        // that submitted the job, showing the full request → queue → process timeline.
        Span span = tracer.nextSpan().name("job.process").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag("job.id", jobId);
            span.tag("job.attempt", String.valueOf(attempt));

            try {
                jobStore.setStatus(jobId, JobStatus.PROCESSING);
                log.info("Job processing started");

                // Uploads arrive via s3://uploads/{jobId}; regular jobs are HTTP/HTTPS URLs.
                boolean isUpload = url.startsWith("s3://uploads/");
                byte[] rawBytes = isUpload
                        ? s3ResultStore.downloadUpload(url.substring("s3://uploads/".length()))
                        : DiscordImageResizer.downloadRaw(url);

                ProcessingResult result;
                if (AnimatedGifProcessor.isAnimatedGif(rawBytes)) {
                    log.info("Detected animated GIF, processing all frames");
                    result = new ProcessingResult(AnimatedGifProcessor.process(rawBytes), "image/gif");
                } else {
                    // ImageIO.read decodes the raw bytes into a BufferedImage (in-memory pixel grid).
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(rawBytes));
                    if (img == null) throw new java.io.IOException("URL did not return a recognized image");
                    BufferedImage resized = DiscordImageResizer.resizeImage(img, 128, 128);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(resized, "png", baos);
                    result = new ProcessingResult(baos.toByteArray(), "image/png");
                }
                s3ResultStore.storeResult(jobId, result);

                // Delete upload only after result is safely stored so retries can re-read the source.
                if (isUpload) {
                    s3ResultStore.deleteUpload(url.substring("s3://uploads/".length()));
                }

                jobStore.setStatus(jobId, JobStatus.COMPLETED);

                log.info("Job completed");
                span.tag("job.outcome", "completed");
                sample.stop(meterRegistry.timer("jobs.processing.duration", "status", "completed"));

            } catch (Exception e) {
                span.error(e);

                if (attempt < maxAttempts) {
                    // Exponential backoff: delay doubles with each attempt to avoid hammering
                    // a temporarily unavailable resource. Formula: baseDelay * 2^(attempt-1)
                    // With defaults (base=10s): attempt 1→10s, attempt 2→20s, attempt 3→40s.
                    // "1L << (attempt - 1)" is a left bit-shift — equivalent to 2^(attempt-1)
                    // but avoids floating-point conversion.
                    long delaySeconds = baseDelaySeconds * (1L << (attempt - 1));
                    jobStore.scheduleRetry(jobId, url, attempt + 1, delaySeconds);
                    jobStore.setStatus(jobId, JobStatus.PENDING);
                    meterRegistry.counter("jobs.retried").increment();
                    span.tag("job.outcome", "retrying");
                    log.warn("Job failed, scheduling retry nextAttempt={} delaySeconds={}", attempt + 1, delaySeconds, e);
                } else {
                    jobStore.setStatus(jobId, JobStatus.FAILED);
                    jobStore.pushToDlq(jobId, url, attempt, e.getMessage());
                    span.tag("job.outcome", "failed");
                    log.error("Job failed after max attempts attempt={}", attempt, e);
                }

                sample.stop(meterRegistry.timer("jobs.processing.duration", "status", "failed"));
            }
        } finally {
            span.end();
            activeJobs.decrementAndGet();
            // Clear MDC so the next job processed by this thread starts with a clean context.
            MDC.clear();
        }
    }
}
