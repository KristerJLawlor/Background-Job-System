package com.krister.avatar.api;

import com.krister.avatar.shared.RedisJobStore;
import com.krister.avatar.shared.RedisJobStore.DlqEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Admin endpoints for managing the Dead Letter Queue (DLQ) — jobs that failed all retry
// attempts. These endpoints are protected by the same API key as the main job API.
@RestController
@RequestMapping("/api/admin/jobs/failed")
public class AdminController {

    private final RedisJobStore jobStore;

    public AdminController(RedisJobStore jobStore) {
        this.jobStore = jobStore;
    }

    @GetMapping
    public List<DlqEntry> listFailed() {
        return jobStore.listDlq();
    }

    // 204 No Content is the standard success response for operations that don't return a body.
    // 404 communicates the job wasn't in the DLQ (already requeued or never existed).
    @PostMapping("/{jobId}/requeue")
    public ResponseEntity<Void> requeue(@PathVariable String jobId) {
        boolean found = jobStore.requeueFromDlq(jobId);
        return found ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{jobId}")
    public ResponseEntity<Void> delete(@PathVariable String jobId) {
        boolean found = jobStore.removeFromDlq(jobId);
        return found ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
