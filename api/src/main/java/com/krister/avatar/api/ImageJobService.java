package com.krister.avatar.api;

import com.krister.avatar.shared.JobStatus;
import com.krister.avatar.shared.ProcessingResult;
import com.krister.avatar.shared.RedisJobStore;
import com.krister.avatar.shared.S3ResultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ImageJobService {

    private static final Logger log = LoggerFactory.getLogger(ImageJobService.class);

    private final RedisJobStore jobStore;
    private final S3ResultStore s3ResultStore;

    public ImageJobService(RedisJobStore jobStore, S3ResultStore s3ResultStore) {
        this.jobStore = jobStore;
        this.s3ResultStore = s3ResultStore;
    }

    public String createJob(String url) {
        String jobId = UUID.randomUUID().toString();
        jobStore.setStatus(jobId, JobStatus.PENDING);
        jobStore.enqueue(jobId, url);
        log.info("Job created jobId={}", jobId);
        return jobId;
    }

    public JobStatus getStatus(String jobId) {
        return jobStore.getStatus(jobId);
    }

    public ProcessingResult claimResult(String jobId) {
        return s3ResultStore.claimResult(jobId);
    }
}
