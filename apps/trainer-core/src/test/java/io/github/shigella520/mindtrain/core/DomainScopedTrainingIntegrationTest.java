package io.github.shigella520.mindtrain.core;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("domain-scoped-training")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DomainScopedTrainingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void resolvesDomainsAndKeepsTrainingSchedulingAndQuestionsScoped() throws Exception {
        mvc.perform(post("/api/v1/sessions").header("Idempotency-Key", "domain-none")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("no_training_domains"));

        insertDomain("domain-a", "Domain A", "topic-a", "Topic A");
        mvc.perform(post("/api/v1/sessions").header("Idempotency-Key", "domain-sole")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.domainId").value("domain-a"))
            .andExpect(jsonPath("$.domainName").value("Domain A"));

        insertDomain("domain-b", "Domain B", "topic-b", "Topic B");
        mvc.perform(post("/api/v1/sessions").header("Idempotency-Key", "domain-multiple")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("training_domain_selection_required"));
        mvc.perform(post("/api/v1/sessions").header("Idempotency-Key", "domain-missing")
                .contentType(MediaType.APPLICATION_JSON).content("{\"domainId\":\"missing\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("training_domain_not_found"));

        TestFixtures.insertTopicAndActiveQuestion(jdbc, objectMapper, "domain-a", "topic-a", "Topic A", 5,
            question("question-a", "topic-a"));
        TestFixtures.insertTopicAndActiveQuestion(jdbc, objectMapper, "domain-b", "topic-b", "Topic B", 5,
            question("question-b", "topic-b"));
        makeDue("question-a");
        makeDue("question-b");

        String sessionA = objectMapper.readTree(mvc.perform(post("/api/v1/sessions")
                .header("Idempotency-Key", "domain-a-session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"domainId\":\"domain-a\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.domainName").value("Domain A"))
            .andReturn().getResponse().getContentAsString()).path("id").asText();
        mvc.perform(post("/api/v1/sessions/{id}/assignments/next", sessionA)
                .header("Idempotency-Key", "domain-a-next"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignment.question.id").value("question-a"));

        mvc.perform(get("/api/v1/schedulers/backlog").param("domainId", "domain-a"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dueCount").value(1));
        mvc.perform(get("/api/v1/schedulers/backlog"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dueCount").value(2));

        mvc.perform(post("/api/v1/candidates").header("Idempotency-Key", "candidate-cross-domain")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + sessionA + "\",\"topicId\":\"topic-b\",\"question\":{}}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("candidate_domain_mismatch"));

        mvc.perform(post("/api/v1/questions/question-a/revisions")
                .header("Idempotency-Key", "revision-cross-domain").contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedVersion":1,"reason":"cross domain test","changes":{"topicIds":["topic-b"]}}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("question_topics_cross_domain"));

        insertDomain("domain-c", "Domain C", "topic-c", "Topic C");
        String sessionC = objectMapper.readTree(mvc.perform(post("/api/v1/sessions")
                .header("Idempotency-Key", "domain-c-session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"domainId\":\"domain-c\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();
        mvc.perform(post("/api/v1/sessions/{id}/assignments/next", sessionC)
                .header("Idempotency-Key", "domain-c-next"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("generation_required"))
            .andExpect(jsonPath("$.generationContext.id").value("topic-c"));
    }

    private void insertDomain(String domainId, String domainName, String topicId, String topicName) {
        jdbc.sql("""
                INSERT INTO knowledge_domain(id,user_id,name,content_json,created_at,origin_type,sort_order,updated_at)
                VALUES (:id,'test-user',:name,'{}',CURRENT_TIMESTAMP,'ai_dialogue',0,CURRENT_TIMESTAMP)
                """).param("id", domainId).param("name", domainName).update();
        jdbc.sql("""
                INSERT INTO topic(id,domain_id,parent_id,name,kind,importance,content_json,created_at,sort_order,updated_at)
                VALUES (:id,:domainId,NULL,:name,'leaf',5,:content,CURRENT_TIMESTAMP,0,CURRENT_TIMESTAMP)
                """).param("id", topicId).param("domainId", domainId).param("name", topicName)
            .param("content", "{\"importance\":5,\"keywords\":[],\"sourceRefs\":[]}").update();
    }

    private void makeDue(String questionId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO review_state(user_id,question_id,correct_count,wrong_count,consecutive_correct,
                  interval_days,last_answered_at,next_review_at)
                VALUES ('test-user',:questionId,0,1,0,1,:lastAnsweredAt,:nextReviewAt)
                """).param("questionId", questionId).param("lastAnsweredAt", now.minusDays(2))
            .param("nextReviewAt", now.minusDays(1)).update();
    }

    private JsonNode question(String id, String topicId) throws Exception {
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        return objectMapper.readTree("""
            {"schemaVersion":1,"id":"%s","version":1,"status":"active","type":"multiple_choice",
             "title":"Scoped question","stem":"Which statements are correct?",
             "options":[{"id":"A","text":"A"},{"id":"B","text":"B"},{"id":"C","text":"C"},{"id":"D","text":"D"}],
             "correctOptionIds":["A","C"],"topicIds":["%s"],"difficulty":3,"importance":5,
             "explanation":{"conclusion":"Test","optionAnalysis":[
               {"optionId":"A","correct":true,"analysis":"yes"},{"optionId":"B","correct":false,"analysis":"no"},
               {"optionId":"C","correct":true,"analysis":"yes"},{"optionId":"D","correct":false,"analysis":"no"}]},
             "sources":[{"url":"https://example.com","title":"Example","accessedAt":"2026-07-22"}],
             "createdBy":"test","promptVersion":"test","createdAt":"%s"}
            """.formatted(id, topicId, now));
    }
}
