package io.github.shigella520.mindtrain.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.mindtrain.core.training.TrainingService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TrainingFlowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcClient jdbc;
    @Autowired TrainingService training;

    @Test
    void runsSafeConversationalTrainingAndSessionScopedCandidateFlow() throws Exception {
        insertFixture();
        String sessionId = createSession("session-create-1");

        JsonNode first = json(mvc.perform(post("/api/v1/sessions/{id}/assignments/next", sessionId)
                .header("Idempotency-Key", "next-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("assignment"))
            .andExpect(jsonPath("$.assignment.answerPrompt").value("请回复选项字母，可用逗号分隔。"))
            .andExpect(jsonPath("$.assignment.question.correctOptionIds").doesNotExist())
            .andExpect(jsonPath("$.assignment.question.explanation").doesNotExist())
            .andReturn().getResponse().getContentAsString());
        String assignmentId = first.path("assignment").path("assignmentId").asText();

        mvc.perform(post("/api/v1/sessions/{id}/interactions", sessionId)
                .header("Idempotency-Key", "interaction-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assignmentId":"%s","eventType":"clarification_question","content":"这里的语义是什么？"}
                    """.formatted(assignmentId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.consumedQuestion").value(false));

        mvc.perform(post("/api/v1/assignments/{id}/attempts", assignmentId)
                .header("Idempotency-Key", "bad-answer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"AtomicInteger 需要 volatile 吗？\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("answer_unparseable"));

        String answer = mvc.perform(post("/api/v1/assignments/{id}/attempts", assignmentId)
                .header("Idempotency-Key", "answer-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"A,C\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.correct").value(true))
            .andExpect(jsonPath("$.score").value(100))
            .andExpect(jsonPath("$.explanation.conclusion").exists())
            .andReturn().getResponse().getContentAsString();

        String repeated = mvc.perform(post("/api/v1/assignments/{id}/attempts", assignmentId)
                .header("Idempotency-Key", "answer-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"A,C\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(repeated).isEqualTo(answer);

        String parentAttemptId = json(answer).path("attemptId").asText();
        JsonNode followUp = candidate("java.concurrency.atomics", "candidate.followup." + UUID.randomUUID());
        JsonNode followUpResponse = json(mvc.perform(post("/api/v1/candidates")
                .header("Idempotency-Key", "follow-up-candidate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("sessionId", sessionId).put("topicId", "java.concurrency.atomics")
                    .put("attemptType", "follow_up").put("parentAttemptId", parentAttemptId)
                    .set("question", followUp))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignmentId").isNotEmpty())
            .andReturn().getResponse().getContentAsString());
        String followUpAssignmentId = followUpResponse.path("assignmentId").asText();
        mvc.perform(post("/api/v1/assignments/{id}/attempts", followUpAssignmentId)
                .header("Idempotency-Key", "follow-up-answer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"AC\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.correct").value(true));

        JsonNode generation = json(mvc.perform(post("/api/v1/sessions/{id}/assignments/next", sessionId)
                .header("Idempotency-Key", "next-2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("generation_required"))
            .andExpect(jsonPath("$.generationProfile.questionType").value("single_choice"))
            .andExpect(jsonPath("$.generationProfile.difficulty").value(4))
            .andExpect(jsonPath("$.generationProfile.knowledgePoint.topicId").value("java.concurrency.atomics"))
            .andExpect(jsonPath("$.generationProfile.knowledgePoint.name").value("Atomic 原子类"))
            .andReturn().getResponse().getContentAsString());
        String topicId = generation.path("generationContext").path("id").asText();
        String questionType = generation.path("generationProfile").path("questionType").asText();
        int difficulty = generation.path("generationProfile").path("difficulty").asInt();

        JsonNode mismatchedCandidate = candidate(topicId, "candidate.mismatch." + UUID.randomUUID());
        mvc.perform(post("/api/v1/candidates")
                .header("Idempotency-Key", "candidate-profile-mismatch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("sessionId", sessionId).put("topicId", topicId).set("question", mismatchedCandidate))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("candidate_profile_mismatch"));

        JsonNode candidate = candidate(topicId, "candidate." + UUID.randomUUID(), questionType, difficulty);
        mvc.perform(post("/api/v1/candidates")
                .header("Idempotency-Key", "candidate-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("sessionId", sessionId).put("topicId", topicId).set("question", candidate))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.usableInCurrentSession").value(true));

        JsonNode generatedAssignment = json(mvc.perform(post("/api/v1/sessions/{id}/assignments/next", sessionId)
                .header("Idempotency-Key", "next-3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("assignment"))
            .andExpect(jsonPath("$.assignment.sourceKind").value("candidate"))
            .andReturn().getResponse().getContentAsString());
        String candidateAssignmentId = generatedAssignment.path("assignment").path("assignmentId").asText();
        String candidateQuestionId = generatedAssignment.path("assignment").path("question").path("id").asText();

        String rejected = mvc.perform(post("/api/v1/assignments/{id}/reject", candidateAssignmentId)
                .header("Idempotency-Key", "reject-candidate-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.physicallyDeleted").value(true))
            .andExpect(jsonPath("$.newItemAllowanceRestored").value(true))
            .andReturn().getResponse().getContentAsString();
        String repeatedRejection = mvc.perform(post("/api/v1/assignments/{id}/reject", candidateAssignmentId)
                .header("Idempotency-Key", "reject-candidate-1"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(repeatedRejection).isEqualTo(rejected);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM assignment WHERE id = :id").param("id", candidateAssignmentId)
            .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM question WHERE id = :id").param("id", candidateQuestionId)
            .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT introduced_new_count FROM training_session WHERE id = :id").param("id", sessionId)
            .query(Integer.class).single()).isEqualTo(1);
        mvc.perform(post("/api/v1/candidates")
                .header("Idempotency-Key", "candidate-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("sessionId", sessionId).put("topicId", topicId).set("question", candidate))))
            .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM question WHERE id = :id").param("id", candidateQuestionId)
            .query(Integer.class).single()).isZero();
        mvc.perform(post("/api/v1/sessions/{id}/assignments/next", sessionId)
                .header("Idempotency-Key", "next-3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("generation_required"));

        JsonNode replacementGeneration = json(mvc.perform(post("/api/v1/sessions/{id}/assignments/next", sessionId)
                .header("Idempotency-Key", "next-4"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("generation_required"))
            .andExpect(jsonPath("$.details.quotaMode").value("planned_new"))
            .andReturn().getResponse().getContentAsString());
        String replacementType = replacementGeneration.path("generationProfile").path("questionType").asText();
        int replacementDifficulty = replacementGeneration.path("generationProfile").path("difficulty").asInt();
        JsonNode replacement = candidate(topicId, "candidate.replacement." + UUID.randomUUID(),
            replacementType, replacementDifficulty);
        mvc.perform(post("/api/v1/candidates")
                .header("Idempotency-Key", "candidate-replacement")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("sessionId", sessionId).put("topicId", topicId).set("question", replacement))))
            .andExpect(status().isOk());
        JsonNode replacementAssignment = json(mvc.perform(post("/api/v1/sessions/{id}/assignments/next", sessionId)
                .header("Idempotency-Key", "next-5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignment.sourceKind").value("candidate"))
            .andReturn().getResponse().getContentAsString());
        String replacementAssignmentId = replacementAssignment.path("assignment").path("assignmentId").asText();
        String replacementQuestionId = replacementAssignment.path("assignment").path("question").path("id").asText();
        String replacementAnswer = String.join(",", objectMapper.convertValue(replacement.path("correctOptionIds"), String[].class));
        mvc.perform(post("/api/v1/assignments/{id}/attempts", replacementAssignmentId)
                .header("Idempotency-Key", "answer-replacement")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.createObjectNode().put("answer", replacementAnswer).toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.correct").value(true));
        assertThat(jdbc.sql("SELECT status FROM question WHERE id = :id").param("id", replacementQuestionId)
            .query(String.class).single()).isEqualTo("active");
        assertThat(jdbc.sql("SELECT session_eligible_id FROM question WHERE id = :id").param("id", replacementQuestionId)
            .query(String.class).optional()).isEmpty();

        mvc.perform(post("/api/v1/assignments/{id}/reject", replacementAssignmentId)
                .header("Idempotency-Key", "reject-answered-candidate"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("candidate_already_answered"));

        JsonNode shortageGeneration = json(mvc.perform(post("/api/v1/sessions/{id}/assignments/next", sessionId)
                .header("Idempotency-Key", "next-6"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("generation_required"))
            .andExpect(jsonPath("$.details.quotaMode").value("shortage_fill"))
            .andReturn().getResponse().getContentAsString());

        String otherSession = createSession("session-create-2");
        JsonNode otherGeneration = json(mvc.perform(post("/api/v1/sessions/{id}/assignments/next", otherSession)
                .header("Idempotency-Key", "other-next"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("generation_required"))
            .andReturn().getResponse().getContentAsString());

        mvc.perform(get("/api/v1/reports/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.attempts").value(3))
            .andExpect(jsonPath("$.accuracy").value(1.0))
            .andExpect(jsonPath("$.todayCompletedMainQuestions").value(2))
            .andExpect(jsonPath("$.dailyTarget").value(10))
            .andExpect(jsonPath("$.reviewBudget").value(8))
            .andExpect(jsonPath("$.newBudget").value(2))
            .andExpect(jsonPath("$.reportingTimeZone").value("Asia/Shanghai"))
            .andExpect(jsonPath("$.schedulerProvider").value("weighted"))
            .andExpect(jsonPath("$.schedulerProviderName").value("加权调度"))
            .andExpect(jsonPath("$.activeQuestions").value(3))
            .andExpect(jsonPath("$.pendingGeneratedQuestions").value(0));

        JsonNode abandoned = candidate(topicId, "candidate.abandoned." + UUID.randomUUID(),
            shortageGeneration.path("generationProfile").path("questionType").asText(),
            shortageGeneration.path("generationProfile").path("difficulty").asInt());
        mvc.perform(post("/api/v1/candidates")
                .header("Idempotency-Key", "candidate-abandoned")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("sessionId", sessionId).put("topicId", topicId).set("question", abandoned))))
            .andExpect(status().isOk());
        JsonNode abandonedAssignment = json(mvc.perform(post("/api/v1/sessions/{id}/assignments/next", sessionId)
                .header("Idempotency-Key", "next-abandoned"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignment.sourceKind").value("candidate"))
            .andReturn().getResponse().getContentAsString());
        String abandonedQuestionId = abandonedAssignment.path("assignment").path("question").path("id").asText();

        mvc.perform(post("/api/v1/sessions/{id}/finish", sessionId)
                .header("Idempotency-Key", "finish-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.completedMainQuestions").value(2))
            .andExpect(jsonPath("$.followUpCount").value(1));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM question WHERE id = :id").param("id", abandonedQuestionId)
            .query(Integer.class).single()).isZero();

        JsonNode expired = candidate(topicId, "candidate.expired." + UUID.randomUUID(),
            otherGeneration.path("generationProfile").path("questionType").asText(),
            otherGeneration.path("generationProfile").path("difficulty").asInt());
        mvc.perform(post("/api/v1/candidates")
                .header("Idempotency-Key", "candidate-expired")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("sessionId", otherSession).put("topicId", topicId).set("question", expired))))
            .andExpect(status().isOk());
        JsonNode expiredAssignment = json(mvc.perform(post("/api/v1/sessions/{id}/assignments/next", otherSession)
                .header("Idempotency-Key", "other-next-expired"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignment.sourceKind").value("candidate"))
            .andReturn().getResponse().getContentAsString());
        String expiredAssignmentId = expiredAssignment.path("assignment").path("assignmentId").asText();
        String expiredQuestionId = expiredAssignment.path("assignment").path("question").path("id").asText();
        jdbc.sql("UPDATE assignment SET created_at = :createdAt WHERE id = :id")
            .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC).minusHours(25))
            .param("id", expiredAssignmentId).update();
        training.cleanupExpiredPendingCandidates();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM assignment WHERE id = :id").param("id", expiredAssignmentId)
            .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM question WHERE id = :id").param("id", expiredQuestionId)
            .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT introduced_new_count FROM training_session WHERE id = :id").param("id", otherSession)
            .query(Integer.class).single()).isZero();
    }

    private void insertFixture() throws Exception {
        JsonNode question = candidate("java.concurrency.atomics", "java.concurrency.atomics.active");
        TestFixtures.insertTopicAndActiveQuestion(jdbc, objectMapper, "test-domain",
            "java.concurrency.atomics", "Atomic 原子类", 5, question);
    }

    private String createSession(String key) throws Exception {
        JsonNode response = json(mvc.perform(post("/api/v1/sessions")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return response.path("id").asText();
    }

    private JsonNode candidate(String topicId, String id) throws Exception {
        return candidate(topicId, id, "multiple_choice", 3);
    }

    private JsonNode candidate(String topicId, String id, String type, int difficulty) throws Exception {
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        JsonNode question = objectMapper.readTree("""
            {
              "schemaVersion":1,"id":"%s","version":1,"status":"candidate","type":"multiple_choice",
              "title":"CAS 语义","stem":"关于 CAS，下列哪些说法正确？",
              "options":[
                {"id":"A","text":"CAS 会比较期望值"},{"id":"B","text":"CAS 保证公平"},
                {"id":"C","text":"CAS 可能重试"},{"id":"D","text":"CAS 必须由 synchronized 实现"}
              ],
              "correctOptionIds":["A","C"],"topicIds":["%s"],"difficulty":3,"importance":5,
              "javaVersions":["8-21"],
              "explanation":{
                "conclusion":"CAS 是条件式原子更新。",
                "optionAnalysis":[
                  {"optionId":"A","correct":true,"analysis":"正确"},
                  {"optionId":"B","correct":false,"analysis":"不保证公平"},
                  {"optionId":"C","correct":true,"analysis":"竞争时可能重试"},
                  {"optionId":"D","correct":false,"analysis":"实现方式不固定"}
                ],
                "mechanism":["读取、比较并更新"],"pitfalls":["把无锁理解成公平"],
                "versionNotes":["适用于 Java 8-21"],"relatedTopicIds":[]
              },
              "sources":[{"sourceId":"src-java-api","url":"https://docs.oracle.com/en/java/","title":"Java API","accessedAt":"2026-07-20"}],
              "parentQuestionId":null,"createdBy":"ai","model":"test","promptVersion":"test-v1",
              "createdAt":"%s","reviewedAt":null
            }
            """.formatted(id, topicId, now));
        var object = (com.fasterxml.jackson.databind.node.ObjectNode) question;
        object.put("type", type);
        object.put("difficulty", difficulty);
        if ("single_choice".equals(type)) {
            var correct = (com.fasterxml.jackson.databind.node.ArrayNode) object.path("correctOptionIds");
            correct.removeAll();
            correct.add("A");
            ((com.fasterxml.jackson.databind.node.ObjectNode) object.path("explanation").path("optionAnalysis").get(2))
                .put("correct", false).put("analysis", "该表述不是本题要求的唯一正确结论");
        }
        return question;
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
