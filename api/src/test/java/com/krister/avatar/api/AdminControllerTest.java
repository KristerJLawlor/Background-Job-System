package com.krister.avatar.api;

import com.krister.avatar.shared.RedisJobStore;
import com.krister.avatar.shared.RedisJobStore.DlqEntry;
import com.krister.avatar.shared.S3ResultStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
class AdminControllerTest {

    static final String API_KEY = "test-key";

    @Autowired MockMvc mvc;
    @MockBean RedisJobStore jobStore;
    @MockBean S3ResultStore s3ResultStore;
    @MockBean GlobalJobQuota globalQuota;

    @Test
    void listFailed_returnsEntries() throws Exception {
        var entry = new DlqEntry("job-1", "https://1.1.1.1/img.png", 3, 1700000000L, "network error");
        when(jobStore.listDlq()).thenReturn(List.of(entry));

        mvc.perform(get("/api/admin/jobs/failed").header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value("job-1"))
                .andExpect(jsonPath("$[0].url").value("https://1.1.1.1/img.png"))
                .andExpect(jsonPath("$[0].attempts").value(3))
                .andExpect(jsonPath("$[0].error").value("network error"));
    }

    @Test
    void listFailed_missingApiKey_returns401() throws Exception {
        mvc.perform(get("/api/admin/jobs/failed"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requeue_existingJob_returns204() throws Exception {
        when(jobStore.requeueFromDlq("job-1")).thenReturn(true);

        mvc.perform(post("/api/admin/jobs/failed/job-1/requeue").header("X-Api-Key", API_KEY))
                .andExpect(status().isNoContent());

        verify(jobStore).requeueFromDlq("job-1");
    }

    @Test
    void requeue_unknownJob_returns404() throws Exception {
        when(jobStore.requeueFromDlq("missing")).thenReturn(false);

        mvc.perform(post("/api/admin/jobs/failed/missing/requeue").header("X-Api-Key", API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_existingJob_returns204() throws Exception {
        when(jobStore.removeFromDlq("job-1")).thenReturn(true);

        mvc.perform(delete("/api/admin/jobs/failed/job-1").header("X-Api-Key", API_KEY))
                .andExpect(status().isNoContent());

        verify(jobStore).removeFromDlq("job-1");
    }

    @Test
    void delete_unknownJob_returns404() throws Exception {
        when(jobStore.removeFromDlq("missing")).thenReturn(false);

        mvc.perform(delete("/api/admin/jobs/failed/missing").header("X-Api-Key", API_KEY))
                .andExpect(status().isNotFound());
    }
}
