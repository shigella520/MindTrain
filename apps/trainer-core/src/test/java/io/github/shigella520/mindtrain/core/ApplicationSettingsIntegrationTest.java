package io.github.shigella520.mindtrain.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationSettingsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void storesTrainingSettingsAndDerivesReviewBudget() throws Exception {
        mvc.perform(get("/api/v1/settings/training"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.questionCount").value(10))
            .andExpect(jsonPath("$.newBudget").value(2))
            .andExpect(jsonPath("$.reviewBudget").value(8))
            .andExpect(jsonPath("$.reportingTimeZone").value("Asia/Shanghai"));

        String payload = """
            {
              "questionCount": 12,
              "newBudget": 3,
              "backlogPauseThreshold": 30,
              "overduePauseDays": 5,
              "pendingCandidateTtlHours": 48,
              "reportingTimeZone": "Asia/Shanghai"
            }
            """;
        String first = mvc.perform(put("/api/v1/settings/training")
                .header("Idempotency-Key", "training-settings-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.questionCount").value(12))
            .andExpect(jsonPath("$.newBudget").value(3))
            .andExpect(jsonPath("$.reviewBudget").value(9))
            .andReturn().getResponse().getContentAsString();
        String repeated = mvc.perform(put("/api/v1/settings/training")
                .header("Idempotency-Key", "training-settings-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(repeated).isEqualTo(first);

        jdbc.sql("""
                INSERT INTO knowledge_domain(id,user_id,name,content_json,created_at,origin_type,sort_order,updated_at)
                VALUES ('settings-domain','test-user','Settings Domain','{}',CURRENT_TIMESTAMP,'legacy',0,CURRENT_TIMESTAMP)
                """).update();
        jdbc.sql("""
                INSERT INTO topic(id,domain_id,parent_id,name,kind,importance,content_json,created_at,sort_order,updated_at)
                VALUES ('settings-topic','settings-domain',NULL,'Settings Topic','leaf',3,'{}',CURRENT_TIMESTAMP,0,CURRENT_TIMESTAMP)
                """).update();

        mvc.perform(post("/api/v1/sessions")
                .header("Idempotency-Key", "settings-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"domainId\":\"settings-domain\",\"questionCount\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.targetCount").value(12));

        mvc.perform(get("/api/v1/reports/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dailyTarget").value(12))
            .andExpect(jsonPath("$.reviewBudget").value(9))
            .andExpect(jsonPath("$.newBudget").value(3));

        assertThat(jdbc.sql("SELECT question_count - new_budget FROM application_training_settings WHERE id = 'default'")
            .query(Integer.class).single()).isEqualTo(9);
    }

    @Test
    void rejectsInvalidNewBudget() throws Exception {
        mvc.perform(put("/api/v1/settings/training")
                .header("Idempotency-Key", "invalid-training-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "questionCount": 5,
                      "newBudget": 6,
                      "backlogPauseThreshold": 20,
                      "overduePauseDays": 3,
                      "pendingCandidateTtlHours": 24,
                      "reportingTimeZone": "Asia/Shanghai"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("training_settings_invalid"));
    }
}
