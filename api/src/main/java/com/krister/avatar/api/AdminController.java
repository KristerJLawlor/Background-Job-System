package com.krister.avatar.api;

import com.krister.avatar.shared.RedisJobStore;
import com.krister.avatar.shared.RedisJobStore.DlqEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
