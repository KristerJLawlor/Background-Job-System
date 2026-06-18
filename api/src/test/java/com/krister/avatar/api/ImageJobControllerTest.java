package com.krister.avatar.api;

import com.krister.avatar.shared.JobStatus;
import com.krister.avatar.shared.ProcessingResult;
import com.krister.avatar.shared.RedisJobStore;
import com.krister.avatar.shared.S3ResultStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ImageJobController.class)
class ImageJobControllerTest {

    // @WebMvcTest disables observability auto-configuration; supply a real registry
    // so counter().increment() calls in the controller don't NPE.
    @TestConfiguration
    static class MetricsConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    static final String API_KEY = "test-key";

    @Autowired MockMvc mvc;
    @MockBean ImageJobService jobService;
    @MockBean IpRateLimiter rateLimiter;
    @MockBean GlobalJobQuota globalQuota;
    // RedisJobStore and S3ResultStore are in the shared component scan path; mock both to
    // prevent context startup failures (@PostConstruct on S3ResultStore calls S3).
    @MockBean RedisJobStore redisJobStore;
    @MockBean S3ResultStore s3ResultStore;

    // --- Authentication ---

    @Test
    void submitJob_missingApiKey_returns401() throws Exception {
        mvc.perform(post("/api/jobs").param("url", "https://1.1.1.1/img.png"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing or invalid API key"));
    }

    @Test
    void submitJob_wrongApiKey_returns401() throws Exception {
        mvc.perform(post("/api/jobs")
                        .header("X-Api-Key", "wrong-key")
                        .param("url", "https://1.1.1.1/img.png"))
                .andExpect(status().isUnauthorized());
    }

    // --- POST /api/jobs ---

    @Test
    void submitJob_rateLimited_returns429() throws Exception {
        when(rateLimiter.tryConsume(any())).thenReturn(false);

        mvc.perform(post("/api/jobs")
                        .header("X-Api-Key", API_KEY)
                        .param("url", "https://1.1.1.1/img.png"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void submitJob_quotaExceeded_returns429() throws Exception {
        when(rateLimiter.tryConsume(any())).thenReturn(true);
        when(globalQuota.tryConsume()).thenReturn(false);

        mvc.perform(post("/api/jobs")
                        .header("X-Api-Key", API_KEY)
                        .param("url", "https://1.1.1.1/img.png"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Daily processing limit reached — try again tomorrow"));
    }

    @Test
    void submitJob_invalidUrl_returns400() throws Exception {
        when(rateLimiter.tryConsume(any())).thenReturn(true);
        when(globalQuota.tryConsume()).thenReturn(true);
        try (MockedStatic<UrlValidator> validator = mockStatic(UrlValidator.class)) {
            validator.when(() -> UrlValidator.validate(anyString()))
                    .thenThrow(new IllegalArgumentException("URL is not allowed"));

            mvc.perform(post("/api/jobs")
                            .header("X-Api-Key", API_KEY)
                            .param("url", "http://10.0.0.1/img.png"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("URL is not allowed"));
        }
    }

    @Test
    void submitJob_validUrl_returnsJobId() throws Exception {
        when(rateLimiter.tryConsume(any())).thenReturn(true);
        when(globalQuota.tryConsume()).thenReturn(true);
        when(jobService.createJob(anyString())).thenReturn("job-abc");
        try (MockedStatic<UrlValidator> validator = mockStatic(UrlValidator.class)) {
            // validate() is void — by default the mock does nothing (URL passes)

            mvc.perform(post("/api/jobs")
                            .header("X-Api-Key", API_KEY)
                            .param("url", "https://1.1.1.1/img.png"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jobId").value("job-abc"));
        }
    }

    // --- GET /api/jobs/{jobId} ---

    @Test
    void getStatus_unknownJob_returns404() throws Exception {
        when(jobService.getStatus("missing")).thenReturn(null);

        mvc.perform(get("/api/jobs/missing").header("X-Api-Key", API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void getStatus_knownJob_returnsStatus() throws Exception {
        when(jobService.getStatus("job-1")).thenReturn(JobStatus.PROCESSING);

        mvc.perform(get("/api/jobs/job-1").header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().string("PROCESSING"));
    }

    // --- GET /api/jobs/{jobId}/result ---

    @Test
    void getResult_jobNotCompleted_returns400() throws Exception {
        when(jobService.getStatus("job-1")).thenReturn(JobStatus.PENDING);

        mvc.perform(get("/api/jobs/job-1/result").header("X-Api-Key", API_KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getResult_alreadyClaimed_returns410() throws Exception {
        when(jobService.getStatus("job-1")).thenReturn(JobStatus.COMPLETED);
        when(jobService.claimResult("job-1")).thenReturn(null);

        mvc.perform(get("/api/jobs/job-1/result").header("X-Api-Key", API_KEY))
                .andExpect(status().isGone());
    }

    @Test
    void getResult_completed_returnsPngBytes() throws Exception {
        byte[] pngBytes = {(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG magic bytes
        when(jobService.getStatus("job-1")).thenReturn(JobStatus.COMPLETED);
        when(jobService.claimResult("job-1")).thenReturn(new ProcessingResult(pngBytes, "image/png"));

        mvc.perform(get("/api/jobs/job-1/result").header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(pngBytes));
    }

    @Test
    void getResult_completedGif_returnsGifBytes() throws Exception {
        byte[] gifBytes = {0x47, 0x49, 0x46, 0x38, 0x39, 0x61}; // GIF89a magic bytes
        when(jobService.getStatus("job-1")).thenReturn(JobStatus.COMPLETED);
        when(jobService.claimResult("job-1")).thenReturn(new ProcessingResult(gifBytes, "image/gif"));

        mvc.perform(get("/api/jobs/job-1/result").header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_GIF))
                .andExpect(content().bytes(gifBytes));
    }
}
