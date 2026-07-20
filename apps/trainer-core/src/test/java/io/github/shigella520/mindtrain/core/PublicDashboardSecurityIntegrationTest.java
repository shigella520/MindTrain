package io.github.shigella520.mindtrain.core;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "mindtrain.security.enabled=true",
    "mindtrain.security.public-dashboard-enabled=true"
})
@AutoConfigureMockMvc
class PublicDashboardSecurityIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void onlyDashboardReadsAreAnonymous() throws Exception {
        mvc.perform(get("/api/v1/reports/overview"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/schedulers/backlog"))
            .andExpect(status().isOk());

        mvc.perform(post("/api/v1/sessions")
                .header("Idempotency-Key", "anonymous-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionCount\":10}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/imports/missing"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/questions/java.concurrency.volatile.001/revisions")
                .header("Idempotency-Key", "anonymous-revision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"changes\":{\"stem\":\"x\"},\"reason\":\"x\"}"))
            .andExpect(status().isUnauthorized());
    }
}
