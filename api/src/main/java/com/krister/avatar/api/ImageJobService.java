package com.krister.avatar.api;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.krister.avatar.core.DiscordImageResizer;

@Service
public class ImageJobService {

    private final Map<String, JobStatus> jobStatuses = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> results = new ConcurrentHashMap<>();

    public String createJob(String url) {
        String jobId = UUID.randomUUID().toString();
        System.out.println("CREATED JOB: " + jobId);

        jobStatuses.put(jobId, JobStatus.PENDING);
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
            e.printStackTrace();  // ADD THIS
            jobStatuses.put(jobId, JobStatus.FAILED);
        }
    }


    public JobStatus getStatus(String jobId) {
        return jobStatuses.get(jobId);
    }

    public BufferedImage getResult(String jobId) {
        return results.get(jobId);
    }

    public Set<String> getAllJobIds() {
        return jobStatuses.keySet();
    }

}
