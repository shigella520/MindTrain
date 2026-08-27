package io.github.shigella520.mindtrain.core;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
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
class QuestionBankIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcClient jdbc;

    @Test
    void hidesUnseenAnswerAndShowsCurrentAnswerOnlyAfterAttempt() throws Exception {
        String id = "bank.question." + UUID.randomUUID();
        String domainId = "bank-domain-" + UUID.randomUUID();
        String topicId = "bank.topic." + UUID.randomUUID();
        TestFixtures.insertTopicAndActiveQuestion(jdbc, objectMapper, domainId, topicId, "Bank topic", 3,
            question(id, topicId));

        mvc.perform(get("/api/v1/questions").param("domainId", domainId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/questions").param("domainId", domainId).param("learningState", "unseen"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].id").value(id))
            .andExpect(jsonPath("$.items[0].correctOptionIds").doesNotExist());
        mvc.perform(get("/api/v1/questions/{id}", id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.answerVisible").value(false))
            .andExpect(jsonPath("$.question.correctOptionIds").doesNotExist())
            .andExpect(jsonPath("$.question.explanation").doesNotExist());

        String sessionId = objectMapper.readTree(mvc.perform(post("/api/v1/sessions")
                .header("Idempotency-Key", "bank-session").contentType(MediaType.APPLICATION_JSON)
                .content("{\"domainId\":\"" + domainId + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();
        String assignmentId = objectMapper.readTree(mvc.perform(post("/api/v1/sessions/{id}/assignments/next", sessionId)
                .header("Idempotency-Key", "bank-next"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.assignment.selectionReason").value("PLANNED_NEW"))
            .andReturn().getResponse().getContentAsString()).path("assignment").path("assignmentId").asText();
        mvc.perform(post("/api/v1/assignments/{id}/attempts", assignmentId)
                .header("Idempotency-Key", "bank-answer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"A\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.correct").value(true));
        mvc.perform(get("/api/v1/questions/{id}", id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.answerVisible").value(true))
            .andExpect(jsonPath("$.question.correctOptionIds[0]").value("A"))
            .andExpect(jsonPath("$.attempts[0].correctOptionIds").doesNotExist());
    }

    private JsonNode question(String id, String topicId) throws Exception {
        return objectMapper.readTree("""
            {"schemaVersion":1,"id":"%s","version":1,"status":"candidate","type":"single_choice",
             "title":"安全题库","stem":"哪个选项正确？",
             "options":[{"id":"A","text":"正确项"},{"id":"B","text":"干扰项"},{"id":"C","text":"干扰项"},{"id":"D","text":"干扰项"}],
             "correctOptionIds":["A"],"topicIds":["%s"],"difficulty":2,"importance":3,
             "explanation":{"conclusion":"A 正确。","optionAnalysis":[],"mechanism":[],"pitfalls":[],"versionNotes":[],"relatedTopicIds":[]},
             "sources":[{"url":"https://example.com","title":"Example","accessedAt":"2026-08-27"}],
             "createdBy":"ai","model":"test","promptVersion":"test","createdAt":"%s"}
            """.formatted(id, topicId, OffsetDateTime.now(ZoneOffset.UTC)));
    }
}
