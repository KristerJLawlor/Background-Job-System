package com.krister.avatar.api;

import com.krister.avatar.shared.JobStatus;
import com.krister.avatar.shared.RedisJobStore;
import com.krister.avatar.shared.S3ResultStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ImageJobServiceTest {

    @Mock RedisJobStore jobStore;
    @Mock S3ResultStore s3ResultStore;

    ImageJobService service;

    @BeforeEach
    void setUp() {
        service = new ImageJobService(jobStore, s3ResultStore);
    }

    @Test
    void createJob_setsStatusPendingAndEnqueues() {
        String jobId = service.createJob("https://1.1.1.1/img.png");

        assertThat(jobId).isNotBlank();
        verify(jobStore).setStatus(jobId, JobStatus.PENDING);
        verify(jobStore).enqueue(jobId, "https://1.1.1.1/img.png");
    }

    @Test
    void getStatus_delegatesToJobStore() {
        service.getStatus("job-1");
        verify(jobStore).getStatus("job-1");
    }

    @Test
    void claimResult_delegatesToS3ResultStore() {
        service.claimResult("job-1");
        verify(s3ResultStore).claimResult("job-1");
    }
}
