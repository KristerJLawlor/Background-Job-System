package com.krister.avatar.api;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import javax.imageio.ImageIO;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class ImageJobController {

    private final ImageJobService jobService;

    public ImageJobController(ImageJobService jobService) {
        this.jobService = jobService;
    }

    // Submit job
    @PostMapping
    public ResponseEntity<?> submitJob(@RequestParam String url) {
        try {
            String jobId = jobService.createJob(url);
            return ResponseEntity.ok(Map.of("jobId", jobId));
        } catch (RejectedExecutionException e) {
            // Thrown by AsyncConfig when the executor queue is full; surface as 429 rather than 500
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Job queue is full — try again later"));
        }
    }

    // Check status
    @GetMapping("/{jobId}")
    public ResponseEntity<String> getStatus(@PathVariable String jobId) {

        System.out.println("CHECKING JOB: " + jobId);
        System.out.println("CURRENT JOBS: " + jobService.getAllJobIds());

        JobStatus status = jobService.getStatus(jobId);

        if (status == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(status.toString());
    }

    // Get result
    @GetMapping("/{jobId}/result")
    public ResponseEntity<byte[]> getResult(@PathVariable String jobId) {
        if (jobService.getStatus(jobId) != JobStatus.COMPLETED) {
            return ResponseEntity.badRequest().build();
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(jobService.getResult(jobId), "png", baos);

            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .body(baos.toByteArray());

        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/test")
public String test() {
    return "CONTROLLER IS ACTIVE";
}

}
