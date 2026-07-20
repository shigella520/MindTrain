package io.github.shigella520.mindtrain.core;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TrainingFlowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void runsSafeConversationalTrainingAndSessionScopedCandidateFlow() throws Exception {
        importFixture();
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

        mvc.perform(post("/api/v1/sessions/{id}/assignments/next", sessionId)
                .header("Idempotency-Key", "next-3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("assignment"))
            .andExpect(jsonPath("$.assignment.sourceKind").value("candidate"));

        String otherSession = createSession("session-create-2");
        mvc.perform(post("/api/v1/sessions/{id}/assignments/next", otherSession)
                .header("Idempotency-Key", "other-next"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("generation_required"));

        mvc.perform(get("/api/v1/reports/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.attempts").value(2))
            .andExpect(jsonPath("$.accuracy").value(1.0))
            .andExpect(jsonPath("$.todayCompletedMainQuestions").value(1))
            .andExpect(jsonPath("$.dailyTarget").value(10))
            .andExpect(jsonPath("$.reviewBudget").value(8))
            .andExpect(jsonPath("$.newBudget").value(2))
            .andExpect(jsonPath("$.reportingTimeZone").value("Asia/Shanghai"))
            .andExpect(jsonPath("$.schedulerProvider").value("weighted"))
            .andExpect(jsonPath("$.schedulerProviderName").value("加权调度"));

        mvc.perform(post("/api/v1/sessions/{id}/finish", sessionId)
                .header("Idempotency-Key", "finish-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.completedMainQuestions").value(1))
            .andExpect(jsonPath("$.followUpCount").value(1));
    }

    private void importFixture() throws Exception {
        JsonNode question = candidate("java.concurrency.atomics", "java.concurrency.atomics.published");
        ((com.fasterxml.jackson.databind.node.ObjectNode) question).put("status", "published");
        JsonNode payload = objectMapper.readTree("""
            {
              "dryRun": false,
              "taxonomy": {"topics": [{
                "id":"java.concurrency.atomics","name":"Atomic 原子类","kind":"leaf","importance":5,
                "javaVersions":["8-21"],"keywords":["CAS"],"sourceRefs":["src-java-api"]
              }]},
              "questions": [], "candidates": [], "sessions": [], "attempts": [], "mastery": {}
            }
            """);
        ((com.fasterxml.jackson.databind.node.ArrayNode) payload.path("questions")).add(question);
        mvc.perform(post("/api/v1/imports/prototype")
                .header("Idempotency-Key", "fixture-import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.report.questionsImported").value(1));
    }

    private String createSession(String key) throws Exception {
        JsonNode response = json(mvc.perform(post("/api/v1/sessions")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questionCount\":10}"))
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
