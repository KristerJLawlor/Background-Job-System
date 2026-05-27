package com.krister.avatar.worker;

import com.krister.avatar.core.DiscordImageResizer;
import com.krister.avatar.shared.JobStatus;
import com.krister.avatar.shared.RedisJobStore;
import com.krister.avatar.shared.S3ResultStore;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobProcessorTest {

    @Mock RedisJobStore jobStore;
    @Mock S3ResultStore s3ResultStore;
    @Mock Tracer tracer;

    JobProcessor processor;

    @BeforeEach
    void setUp() {
        Span mockSpan = mock(Span.class, RETURNS_DEEP_STUBS);
        lenient().when(tracer.nextSpan()).thenReturn(mockSpan);
        lenient().when(tracer.withSpan(any())).thenReturn(mock(Tracer.SpanInScope.class));

        processor = new JobProcessor(jobStore, s3ResultStore, new SimpleMeterRegistry(), tracer);
        ReflectionTestUtils.setField(processor, "maxAttempts", 3);
        ReflectionTestUtils.setField(processor, "baseDelaySeconds", 10L);
    }

    @Test
    void process_success_setsCompletedAndStoresResult() throws Exception {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);

        try (MockedStatic<DiscordImageResizer> mocked = mockStatic(DiscordImageResizer.class)) {
            mocked.when(() -> DiscordImageResizer.downloadAndResize(anyString())).thenReturn(img);

            processor.process("job-1", "https://1.1.1.1/img.png", 1);
        }

        verify(jobStore).setStatus("job-1", JobStatus.PROCESSING);
        verify(s3ResultStore).storeResult(eq("job-1"), any(byte[].class));
        verify(jobStore).setStatus("job-1", JobStatus.COMPLETED);
        verify(jobStore, never()).scheduleRetry(any(), any(), anyInt(), anyLong());
    }

    @Test
    void process_failureWithAttemptsRemaining_schedulesRetry() {
        try (MockedStatic<DiscordImageResizer> mocked = mockStatic(DiscordImageResizer.class)) {
            mocked.when(() -> DiscordImageResizer.downloadAndResize(anyString()))
                    .thenThrow(new IOException("network error"));

            processor.process("job-1", "https://1.1.1.1/img.png", 1);
        }

        // attempt 1 failed → retry at attempt 2, delay = 10s * 2^0 = 10s
        verify(jobStore).scheduleRetry("job-1", "https://1.1.1.1/img.png", 2, 10L);
        verify(jobStore).setStatus("job-1", JobStatus.PENDING);
        verify(jobStore, never()).setStatus("job-1", JobStatus.FAILED);
    }

    @Test
    void process_failureOnSecondAttempt_doublesBackoffDelay() {
        try (MockedStatic<DiscordImageResizer> mocked = mockStatic(DiscordImageResizer.class)) {
            mocked.when(() -> DiscordImageResizer.downloadAndResize(anyString()))
                    .thenThrow(new IOException("network error"));

            processor.process("job-1", "https://1.1.1.1/img.png", 2);
        }

        // attempt 2 failed → retry at attempt 3, delay = 10s * 2^1 = 20s
        verify(jobStore).scheduleRetry("job-1", "https://1.1.1.1/img.png", 3, 20L);
    }

    @Test
    void process_failureOnFinalAttempt_setsFailedAndPushesToDlq() {
        try (MockedStatic<DiscordImageResizer> mocked = mockStatic(DiscordImageResizer.class)) {
            mocked.when(() -> DiscordImageResizer.downloadAndResize(anyString()))
                    .thenThrow(new IOException("network error"));

            processor.process("job-1", "https://1.1.1.1/img.png", 3);
        }

        verify(jobStore).setStatus("job-1", JobStatus.FAILED);
        verify(jobStore).pushToDlq(eq("job-1"), eq("https://1.1.1.1/img.png"), eq(3), anyString());
        verify(jobStore, never()).scheduleRetry(any(), any(), anyInt(), anyLong());
    }
}
