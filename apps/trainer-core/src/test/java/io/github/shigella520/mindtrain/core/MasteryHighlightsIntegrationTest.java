package io.github.shigella520.mindtrain.core;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mastery-highlights")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MasteryHighlightsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void insertMasteryFixture() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO knowledge_domain(id,user_id,name,content_json,created_at,origin_type,sort_order,updated_at)
                VALUES ('mastery-domain','test-user','系统设计','{}',:now,'ai_dialogue',0,:now)
                """).param("now", now).update();
        insertTopic("mastery-root", null, "基础能力", "category", 0, now);
        insertTopic("mastery-weak", "mastery-root", "缓存一致性", "leaf", 1, now);
        insertTopic("mastery-strong", "mastery-root", "高可用", "leaf", 2, now);
        insertTopic("mastery-building", "mastery-root", "容量规划", "leaf", 3, now);
        insertTopic("mastery-neutral", "mastery-root", "消息队列", "leaf", 4, now);
        insertMastery("mastery-weak", 55, 1, 1, now.minusDays(1));
        insertMastery("mastery-strong", 82, 3, 0, now.minusHours(2));
        insertMastery("mastery-building", 35, 0, 1, now.minusHours(1));
        insertMastery("mastery-neutral", 70, 3, 1, now.minusHours(3));
    }

    @Test
    void classifiesMasteryOnlyAfterEnoughEvidenceAndIncludesCatalogContext() throws Exception {
        mvc.perform(get("/api/v1/reports/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.weakTopics.length()").value(1))
            .andExpect(jsonPath("$.weakTopics[0].topic_id").value("mastery-weak"))
            .andExpect(jsonPath("$.weakTopics[0].domain_name").value("系统设计"))
            .andExpect(jsonPath("$.weakTopics[0].topic_path").value("基础能力 / 缓存一致性"))
            .andExpect(jsonPath("$.weakTopics[0].attempt_count").value(2))
            .andExpect(jsonPath("$.strongTopics.length()").value(1))
            .andExpect(jsonPath("$.strongTopics[0].topic_id").value("mastery-strong"))
            .andExpect(jsonPath("$.strongTopics[0].mastery_score").value(82))
            .andExpect(jsonPath("$.insufficientEvidenceTopicCount").value(1))
            .andExpect(jsonPath("$.insufficientEvidenceTopics[0].topic_id").value("mastery-building"));
    }

    private void insertTopic(String id, String parentId, String name, String kind, int sortOrder, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO topic(id,domain_id,parent_id,name,kind,importance,content_json,created_at,sort_order,updated_at)
                VALUES (:id,'mastery-domain',:parentId,:name,:kind,3,'{}',:now,:sortOrder,:now)
                """).param("id", id).param("parentId", parentId).param("name", name).param("kind", kind)
            .param("sortOrder", sortOrder).param("now", now).update();
    }

    private void insertMastery(String topicId, int score, int correct, int wrong, OffsetDateTime answeredAt) {
        jdbc.sql("""
                INSERT INTO topic_mastery(user_id,topic_id,mastery_score,correct_count,wrong_count,last_answered_at)
                VALUES ('test-user',:topicId,:score,:correct,:wrong,:answeredAt)
                """).param("topicId", topicId).param("score", score).param("correct", correct)
            .param("wrong", wrong).param("answeredAt", answeredAt).update();
    }
}
