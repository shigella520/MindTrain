package io.github.shigella520.mindtrain.core;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SchedulerBacklogIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void exposesConfiguredDailyPlanAndUsesItAsTheDefaultSessionTarget() throws Exception {
        mvc.perform(post("/api/v1/sessions")
                .header("Idempotency-Key", "default-daily-target")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.targetCount").value(10));

        mvc.perform(get("/api/v1/reports/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.todayCompletedMainQuestions").value(0))
            .andExpect(jsonPath("$.dailyTarget").value(10))
            .andExpect(jsonPath("$.reviewBudget").value(8))
            .andExpect(jsonPath("$.newBudget").value(2))
            .andExpect(jsonPath("$.reportingTimeZone").value("Asia/Shanghai"));
    }

    @Test
    void pausesNewItemsWhenBacklogExceedsThresholdOrIsSeverelyOverdue() throws Exception {
        mvc.perform(get("/api/v1/schedulers/backlog"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dueCount").value(0))
            .andExpect(jsonPath("$.newItemAllowance").value(2))
            .andExpect(jsonPath("$.newItemsPaused").value(false));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (int index = 0; index < 21; index++) {
            String id = "backlog-question-" + index;
            jdbc.sql("INSERT INTO question(id, status, current_version, created_at) VALUES (:id, 'published', 1, :createdAt)")
                .param("id", id).param("createdAt", now.minusDays(10)).update();
            jdbc.sql("""
                    INSERT INTO review_state(user_id, question_id, correct_count, wrong_count, consecutive_correct,
                      interval_days, last_answered_at, next_review_at)
                    VALUES ('test-user', :questionId, 0, 1, 0, 1, :lastAnsweredAt, :nextReviewAt)
                    """).param("questionId", id).param("lastAnsweredAt", now.minusDays(5))
                .param("nextReviewAt", now.minusDays(4)).update();
        }

        mvc.perform(get("/api/v1/schedulers/backlog"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dueCount").value(21))
            .andExpect(jsonPath("$.newItemAllowance").value(0))
            .andExpect(jsonPath("$.newItemsPaused").value(true));
    }
}
