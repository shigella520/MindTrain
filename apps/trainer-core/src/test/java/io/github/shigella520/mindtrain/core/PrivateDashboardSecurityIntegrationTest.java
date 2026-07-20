package io.github.shigella520.mindtrain.core;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "mindtrain.security.enabled=true",
    "mindtrain.security.public-dashboard-enabled=false"
})
@AutoConfigureMockMvc
class PrivateDashboardSecurityIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void dashboardRemainsPrivateByDefault() throws Exception {
        mvc.perform(get("/api/v1/reports/overview"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/reports/overview")
                .header("Authorization", "Bearer test-token"))
            .andExpect(status().isOk());
    }
}
