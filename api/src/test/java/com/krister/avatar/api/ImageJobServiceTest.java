package com.krister.avatar.api;

import com.krister.avatar.core.DiscordImageResizer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageJobServiceTest {

    @Mock RedisJobStore jobStore;
    @Mock S3ResultStore s3ResultStore;
    @Mock Tracer tracer;

    ImageJobService service;

    @BeforeEach
    void setUp() {
        Span mockSpan = mock(Span.class, RETURNS_DEEP_STUBS);
        lenient().when(tracer.nextSpan()).thenReturn(mockSpan);
        lenient().when(tracer.withSpan(any())).thenReturn(mock(Tracer.SpanInScope.class));

        service = new ImageJobService(jobStore, s3ResultStore, new SimpleMeterRegistry(), tracer);
        ReflectionTestUtils.setField(service, "maxAttempts", 3);
        ReflectionTestUtils.setField(service, "baseDelaySeconds", 10L);
    }

    @Test
    void createJob_setsStatusPendingAndEnqueues() {
        String jobId = service.createJob("https://1.1.1.1/img.png");

        assertThat(jobId).isNotBlank();
        verify(jobStore).setStatus(jobId, JobStatus.PENDING);
        verify(jobStore).enqueue(jobId, "https://1.1.1.1/img.png");
    }

    @Test
    void processJob_success_setsCompletedAndStoresResult() throws Exception {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);

        try (MockedStatic<DiscordImageResizer> mocked = mockStatic(DiscordImageResizer.class)) {
            mocked.when(() -> DiscordImageResizer.downloadAndResize(anyString())).thenReturn(img);

            service.processJob("job-1", "https://1.1.1.1/img.png", 1);
        }

        verify(jobStore).setStatus("job-1", JobStatus.PROCESSING);
        verify(s3ResultStore).storeResult(eq("job-1"), any(byte[].class));
        verify(jobStore).setStatus("job-1", JobStatus.COMPLETED);
        verify(jobStore, never()).scheduleRetry(any(), any(), anyInt(), anyLong());
    }

    @Test
    void processJob_failureWithAttemptsRemaining_schedulesRetry() {
        try (MockedStatic<DiscordImageResizer> mocked = mockStatic(DiscordImageResizer.class)) {
            mocked.when(() -> DiscordImageResizer.downloadAndResize(anyString()))
                    .thenThrow(new IOException("network error"));

            service.processJob("job-1", "https://1.1.1.1/img.png", 1);
        }

        // attempt 1 failed → retry at attempt 2, delay = 10s * 2^0 = 10s
        verify(jobStore).scheduleRetry("job-1", "https://1.1.1.1/img.png", 2, 10L);
        verify(jobStore).setStatus("job-1", JobStatus.PENDING);
        verify(jobStore, never()).setStatus("job-1", JobStatus.FAILED);
    }

    @Test
    void processJob_failureOnSecondAttempt_doublesBackoffDelay() {
        try (MockedStatic<DiscordImageResizer> mocked = mockStatic(DiscordImageResizer.class)) {
            mocked.when(() -> DiscordImageResizer.downloadAndResize(anyString()))
                    .thenThrow(new IOException("network error"));

            service.processJob("job-1", "https://1.1.1.1/img.png", 2);
        }

        // attempt 2 failed → retry at attempt 3, delay = 10s * 2^1 = 20s
        verify(jobStore).scheduleRetry("job-1", "https://1.1.1.1/img.png", 3, 20L);
    }

    @Test
    void processJob_failureOnFinalAttempt_setsFailed() {
        try (MockedStatic<DiscordImageResizer> mocked = mockStatic(DiscordImageResizer.class)) {
            mocked.when(() -> DiscordImageResizer.downloadAndResize(anyString()))
                    .thenThrow(new IOException("network error"));

            service.processJob("job-1", "https://1.1.1.1/img.png", 3); // attempt == maxAttempts
        }

        verify(jobStore).setStatus("job-1", JobStatus.FAILED);
        verify(jobStore, never()).scheduleRetry(any(), any(), anyInt(), anyLong());
    }
}
