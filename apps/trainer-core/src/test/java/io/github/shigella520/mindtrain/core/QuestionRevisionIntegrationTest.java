package io.github.shigella520.mindtrain.core;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuestionRevisionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcClient jdbc;

    @Test
    void createsAuditedImmutableQuestionRevisionAndRejectsStaleUpdates() throws Exception {
        String questionId = "java.concurrency.volatile." + UUID.randomUUID();
        insertActiveQuestion(questionId);
        String sessionId = createSession();
        JsonNode assignment = json(mvc.perform(post("/api/v1/sessions/{id}/assignments/next", sessionId)
                .header("Idempotency-Key", "revision-next"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignment.question.version").value(1))
            .andReturn().getResponse().getContentAsString()).path("assignment");
        String assignmentId = assignment.path("assignmentId").asText();

        JsonNode request = objectMapper.createObjectNode()
            .put("expectedVersion", 1)
            .put("reason", "拆分中英文术语并补充必要解释")
            .put("sourceAssignmentId", assignmentId)
            .put("model", "test-model")
            .put("promptVersion", "question-revision-v1")
            .set("changes", objectMapper.readTree("""
                {
                  "title":"volatile 内存语义",
                  "stem":"关于 Java 中 volatile 关键字的语义，下列说法正确的是哪些？",
                  "options":[
                    {"id":"A","text":"对 volatile 字段的写入 happens-before 随后对该字段的读取。"},
                    {"id":"B","text":"volatile 可以使 i++ 复合操作具备原子性。"},
                    {"id":"C","text":"volatile 写入的值对随后读取该变量的线程可见。"},
                    {"id":"D","text":"volatile 等价于为所有访问自动添加 synchronized。"}
                  ]
                }
                """));

        String response = mvc.perform(post("/api/v1/questions/{id}/revisions", questionId)
                .header("Idempotency-Key", "revision-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.previousVersion").value(1))
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.status").value("active"))
            .andReturn().getResponse().getContentAsString();

        String repeated = mvc.perform(post("/api/v1/questions/{id}/revisions", questionId)
                .header("Idempotency-Key", "revision-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(repeated).isEqualTo(response);

        assertThat(jdbc.sql("SELECT current_version FROM question WHERE id = :id")
            .param("id", questionId).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM question_version WHERE question_id = :id")
            .param("id", questionId).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM question_revision WHERE question_id = :id")
            .param("id", questionId).query(Integer.class).single()).isEqualTo(1);

        JsonNode versionOne = storedQuestion(questionId, 1);
        JsonNode versionTwo = storedQuestion(questionId, 2);
        assertThat(versionOne.path("stem").asText()).contains("volatile，下列哪些说法正确");
        assertThat(versionTwo.path("stem").asText()).contains("volatile 关键字的语义");
        assertThat(versionTwo.path("createdBy").asText()).isEqualTo("ai");

        mvc.perform(post("/api/v1/assignments/{id}/attempts", assignmentId)
                .header("Idempotency-Key", "revision-old-assignment-answer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"AC\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.correct").value(true));
        assertThat(jdbc.sql("SELECT question_version FROM attempt WHERE assignment_id = :id")
            .param("id", assignmentId).query(Integer.class).single()).isEqualTo(1);

        mvc.perform(post("/api/v1/questions/{id}/revisions", questionId)
                .header("Idempotency-Key", "revision-stale")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("question_version_conflict"));

        JsonNode invalid = objectMapper.createObjectNode()
            .put("expectedVersion", 2)
            .put("reason", "invalid field")
            .set("changes", objectMapper.createObjectNode().put("id", "replacement"));
        mvc.perform(post("/api/v1/questions/{id}/revisions", questionId)
                .header("Idempotency-Key", "revision-invalid-field")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalid)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("revision_changes_invalid"));
    }

    private void insertActiveQuestion(String questionId) throws Exception {
        JsonNode question = question(questionId);
        TestFixtures.insertTopicAndActiveQuestion(jdbc, objectMapper, "test-domain",
            "java.concurrency.volatile", "volatile", 5, question);
    }

    private String createSession() throws Exception {
        return json(mvc.perform(post("/api/v1/sessions")
                .header("Idempotency-Key", "revision-session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"domainId\":\"test-domain\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();
    }

    private JsonNode storedQuestion(String questionId, int version) throws Exception {
        String content = jdbc.sql("""
                SELECT content_json FROM question_version
                WHERE question_id = :id AND version = :version
                """).param("id", questionId).param("version", version).query(String.class).single();
        return objectMapper.readTree(content);
    }

    private JsonNode question(String id) throws Exception {
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        return objectMapper.readTree("""
            {
              "schemaVersion":1,"id":"%s","version":1,"status":"candidate","type":"multiple_choice",
              "title":"volatile 语义","stem":"关于 Java 中 volatile，下列哪些说法正确？",
              "options":[
                {"id":"A","text":"写入与随后读取存在 happens-before 关系"},
                {"id":"B","text":"可以保证 i++ 原子性"},
                {"id":"C","text":"可以保证线程可见性"},
                {"id":"D","text":"等价于 synchronized"}
              ],
              "correctOptionIds":["A","C"],"topicIds":["java.concurrency.volatile"],
              "difficulty":3,"importance":5,"javaVersions":["8-21"],
              "explanation":{
                "conclusion":"volatile 提供可见性和特定有序性。",
                "optionAnalysis":[
                  {"optionId":"A","correct":true,"analysis":"正确"},
                  {"optionId":"B","correct":false,"analysis":"复合操作不具备原子性"},
                  {"optionId":"C","correct":true,"analysis":"正确"},
                  {"optionId":"D","correct":false,"analysis":"不提供互斥"}
                ],
                "mechanism":["volatile 读写参与 happens-before"],"pitfalls":["把可见性当作原子性"],
                "versionNotes":["适用于 Java 8-21"],"relatedTopicIds":[]
              },
              "sources":[{"sourceId":"src-jls","url":"https://docs.oracle.com/javase/specs/","title":"JLS","accessedAt":"2026-07-20"}],
              "parentQuestionId":null,"createdBy":"ai","model":"test","promptVersion":"test-v1",
              "createdAt":"%s","reviewedAt":null
            }
            """.formatted(id, now));
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
