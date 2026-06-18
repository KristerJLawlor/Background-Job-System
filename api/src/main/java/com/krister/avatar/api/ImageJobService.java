package com.krister.avatar.api;

import com.krister.avatar.shared.JobStatus;
import com.krister.avatar.shared.ProcessingResult;
import com.krister.avatar.shared.RedisJobStore;
import com.krister.avatar.shared.S3ResultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

// @Service is semantically equivalent to @Component — both register the class as a
// Spring-managed singleton. @Service signals intent: this class contains business logic
// rather than infrastructure (web layer or data access).
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
        // UUID (Universally Unique Identifier) generates a random 128-bit ID that is
        // practically guaranteed to be unique globally — no database sequence or coordination
        // between servers needed. Format: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx".
        String jobId = UUID.randomUUID().toString();
        jobStore.setStatus(jobId, JobStatus.PENDING);
        jobStore.enqueue(jobId, url);
        log.info("Job created jobId={}", jobId);
        return jobId;
    }

    public String createJobFromUpload(byte[] data, String contentType) {
        String jobId = UUID.randomUUID().toString();
        // Upload bytes are stored in S3 first, before touching the queue, so the worker
        // never dequeues a job whose source data hasn't been written yet.
        s3ResultStore.storeUpload(jobId, data, contentType);
        jobStore.setStatus(jobId, JobStatus.PENDING);
        // The "s3://uploads/" scheme is an internal convention — not a real S3 URL —
        // that tells the worker to fetch bytes from S3 rather than from an HTTP URL.
        jobStore.enqueue(jobId, "s3://uploads/" + jobId);
        log.info("Upload job created jobId={}", jobId);
        return jobId;
    }

    public JobStatus getStatus(String jobId) {
        return jobStore.getStatus(jobId);
    }

    public ProcessingResult claimResult(String jobId) {
        return s3ResultStore.claimResult(jobId);
    }
}
