package com.krister.avatar.api;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.krister.avatar.core.DiscordImageResizer;

@Service
public class ImageJobService {

    private final Map<String, JobStatus> jobStatuses = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> results = new ConcurrentHashMap<>();
    // tracks creation time for each job so the eviction sweep knows what to expire
    private final Map<String, Instant> createdAt = new ConcurrentHashMap<>();

    @Value("${job.result.ttl-minutes:60}")
    private long ttlMinutes;

    public String createJob(String url) {
        String jobId = UUID.randomUUID().toString();
        System.out.println("CREATED JOB: " + jobId);

        jobStatuses.put(jobId, JobStatus.PENDING);
        createdAt.put(jobId, Instant.now());
        processJob(jobId, url);
        return jobId;
    }

    @Async
    public void processJob(String jobId, String url) {
        try {
            jobStatuses.put(jobId, JobStatus.PROCESSING);

            BufferedImage resized = DiscordImageResizer.downloadAndResize(url);

            results.put(jobId, resized);
            jobStatuses.put(jobId, JobStatus.COMPLETED);

        } catch (Exception e) {
            e.printStackTrace();
            jobStatuses.put(jobId, JobStatus.FAILED);
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
        createdAt.entrySet().removeIf(entry -> {
            if (entry.getValue().isBefore(cutoff)) {
                String jobId = entry.getKey();
                jobStatuses.remove(jobId);
                results.remove(jobId);
                return true;
            }
            return false;
        });
    }

    public JobStatus getStatus(String jobId) {
        return jobStatuses.get(jobId);
    }

    public Set<String> getAllJobIds() {
        return jobStatuses.keySet();
    }

}
