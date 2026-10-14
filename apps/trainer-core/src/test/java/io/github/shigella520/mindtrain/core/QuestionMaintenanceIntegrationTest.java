package io.github.shigella520.mindtrain.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class QuestionMaintenanceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    @Test
    void revisesAfterAnswerUsingOlderSourceAndPreservesHistory() throws Exception {
        Fixture f = fixture();
        write("/assignments/" + f.assignment + "/attempts", "{\"answer\":\"A\"}", 200);
        var context = read("/questions/" + f.id + "/revision-context?assignmentId=" + f.assignment);
        assertThat(context.path("sourceAssignmentStatus").asText()).isEqualTo("answered");
        String revision = "{\"expectedVersion\":%d,\"sourceAssignmentId\":\"%s\",\"reason\":\"clarify\",\"changes\":{\"title\":\"revised\"}}";
        write("/questions/" + f.id + "/revisions", revision.formatted(1, f.assignment), 200);
        write("/questions/" + f.id + "/revisions", revision.formatted(2, f.assignment), 200);
        var detail = read("/questions/" + f.id);
        assertThat(detail.path("summary").path("version").asInt()).isEqualTo(3);
        assertThat(detail.path("answerVisible").asBoolean()).isFalse();
        assertThat(detail.path("question").has("correctOptionIds")).isFalse();
        assertThat(detail.path("attempts").get(0).path("question").path("title").asText()).isEqualTo("original");
        assertThat(detail.path("attempts").get(0).path("score").asInt()).isEqualTo(100);
        var rebind = mapper.readTree(revision.formatted(3, f.assignment));
        ((com.fasterxml.jackson.databind.node.ObjectNode) rebind).put("applyToPendingAssignment", true);
        assertThat(write("/questions/" + f.id + "/revisions", rebind.toString(), 409).path("code").asText())
            .isEqualTo("revision_assignment_not_pending");
        assertThat(read("/questions/" + f.id + "/revision-context").path("version").asInt()).isEqualTo(3);
    }

    @Test
    void deletesPendingQuestionReleasesReservationAndRestoresWithoutRevivingAssignment() throws Exception {
        Fixture f = fixture();
        assertThat(sessionCount(f.session, "introduced_new_count")).isEqualTo(1);
        String key = UUID.randomUUID().toString();
        String body = "{\"expectedVersion\":1,\"reason\":\"unhelpful\"}";
        JsonNode deleted = write("/questions/" + f.id + "/delete", body, 200, key);
        assertThat(deleted.path("cancelledAssignmentCount").asInt()).isEqualTo(1);
        assertThat(write("/questions/" + f.id + "/delete", body, 200, key)).isEqualTo(deleted);
        write("/questions/" + f.id + "/delete", body, 200);
        assertThat(events(f.id)).isEqualTo(1);
        assertThat(sessionCount(f.session, "introduced_new_count")).isZero();
        assertThat(sessionCount(f.session, "completed_main")).isZero();
        assertThat(read("/questions?domainId=" + f.domain + "&learningState=all").path("total").asInt()).isZero();
        assertThat(read("/questions?domainId=" + f.domain + "&status=deleted&learningState=all").path("total").asInt()).isEqualTo(1);
        assertThat(read("/questions/" + f.id).path("question").has("correctOptionIds")).isFalse();
        assertThat(read("/catalog/domains/" + f.domain + "/tree").path("domain").path("activeQuestionCount").asInt()).isZero();
        assertThat(write("/assignments/" + f.assignment + "/attempts", "{\"answer\":\"A\"}", 409).path("code").asText()).isEqualTo("question_deleted");
        assertThat(write("/sessions/" + f.session + "/assignments/next", "{}", 200).path("status").asText()).isEqualTo("generation_required");
        write("/questions/" + f.id + "/revisions", "{\"expectedVersion\":1,\"reason\":\"edit\",\"changes\":{\"title\":\"x\"}}", 409);
        write("/questions/" + f.id + "/restore", "{\"expectedVersion\":1}", 200);
        write("/questions/" + f.id + "/restore", "{\"expectedVersion\":1}", 200);
        assertThat(events(f.id)).isEqualTo(2);
        assertThat(write("/assignments/" + f.assignment + "/attempts", "{\"answer\":\"A\"}", 409).path("code").asText()).isEqualTo("assignment_cancelled");
        var next = write("/sessions/" + f.session + "/assignments/next", "{}", 200).path("assignment");
        assertThat(next.path("question").path("id").asText()).isEqualTo(f.id);
        assertThat(next.path("assignmentId").asText()).isNotEqualTo(f.assignment);
        assertThat(sessionCount(f.session, "introduced_new_count")).isEqualTo(1);
    }

    @Test
    void preservesReviewDueDateAndRejectsStaleOrForeignOperations() throws Exception {
        Fixture f = fixture();
        write("/assignments/" + f.assignment + "/attempts", "{\"answer\":\"A\"}", 200);
        jdbc.sql("UPDATE review_state SET next_review_at=TIMESTAMP WITH TIME ZONE '2020-01-01 00:00:00+00' WHERE question_id=:id")
            .param("id", f.id).update();
        write("/questions/" + f.id + "/delete", "{\"expectedVersion\":2,\"reason\":\"stale\"}", 409);
        write("/questions/" + f.id + "/delete", "{\"expectedVersion\":1,\"reason\":\"remove\"}", 200);
        assertThat(read("/schedulers/backlog?domainId=" + f.domain).path("dueCount").asInt()).isZero();
        assertThat(read("/questions/" + f.id).path("attempts").size()).isEqualTo(1);
        write("/questions/" + f.id + "/restore", "{\"expectedVersion\":1}", 200);
        assertThat(read("/schedulers/backlog?domainId=" + f.domain).path("dueCount").asInt()).isEqualTo(1);
        assertThat(sessionCount(f.session, "introduced_new_count")).isEqualTo(1);
        assertThat(sessionCount(f.session, "completed_main")).isEqualTo(1);
        jdbc.sql("INSERT INTO app_user(id,display_name,token_hash,created_at) VALUES ('maintenance-other','other',:hash,CURRENT_TIMESTAMP)")
            .param("hash", "f".repeat(64)).update();
        jdbc.sql("""
            INSERT INTO knowledge_domain(id,user_id,name,content_json,created_at,updated_at,origin_type)
            VALUES ('maintenance-other-domain','maintenance-other','Other','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'ai_dialogue')
            """).update();
        jdbc.sql("UPDATE question SET user_id='maintenance-other',domain_id='maintenance-other-domain' WHERE id=:id").param("id", f.id).update();
        write("/questions/" + f.id + "/delete", "{\"expectedVersion\":1,\"reason\":\"foreign\"}", 404);
        write("/questions/" + f.id + "/restore", "{\"expectedVersion\":1}", 404);
        mvc.perform(get("/api/v1/questions/" + f.id + "/revision-context")).andExpect(status().isNotFound());
    }

    @Test
    void serializesConcurrentDeleteAnswerAndDuplicateDeletes() throws Exception {
        Fixture f = fixture();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CyclicBarrier(2);
            var answer = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/assignments/" + f.assignment + "/attempts")
                    .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"answer\":\"A\"}")).andReturn().getResponse().getStatus();
            });
            var delete = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return write("/questions/" + f.id + "/delete", "{\"expectedVersion\":1,\"reason\":\"remove\"}", 200);
            });
            assertThat(answer.get(10, TimeUnit.SECONDS)).isIn(200, 409);
            delete.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM assignment WHERE question_id=:id AND status='pending'")
                .param("id", f.id).query(Integer.class).single()).isZero();
            assertThat(sessionCount(f.session, "introduced_new_count")).isEqualTo(sessionCount(f.session, "completed_main"));
            Fixture duplicate = fixture();
            var retryStart = new CyclicBarrier(2);
            String key = UUID.randomUUID().toString();
            java.util.concurrent.Callable<JsonNode> operation = () -> {
                retryStart.await(5, TimeUnit.SECONDS);
                return write("/questions/" + duplicate.id + "/delete", "{\"expectedVersion\":1,\"reason\":\"remove\"}", 200, key);
            };
            var first = executor.submit(operation);
            var second = executor.submit(operation);
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
            assertThat(events(duplicate.id)).isEqualTo(1);
        }
    }

    private Fixture fixture() throws Exception {
        String id = "maintenance-" + UUID.randomUUID();
        String domain = id + "-domain", topic = id + "-topic";
        JsonNode question = mapper.readTree("""
            {"id":"%s","version":1,"status":"active","type":"single_choice","title":"original","stem":"Which?",
             "options":[{"id":"A","text":"yes"},{"id":"B","text":"no"},{"id":"C","text":"no"},{"id":"D","text":"no"}],
             "correctOptionIds":["A"],"topicIds":["%s"],"difficulty":2,"importance":3,
             "explanation":{"conclusion":"yes","optionAnalysis":[{},{},{},{}]},
             "sources":[{"url":"https://example.com","title":"Test reference","accessedAt":"2026-10-14"}],
             "createdBy":"user","promptVersion":"test","createdAt":"2026-10-14T00:00:00Z"}
            """.formatted(id, topic));
        TestFixtures.insertTopicAndActiveQuestion(jdbc, mapper, domain, topic, "Maintenance", 3, question);
        String session = write("/sessions", "{\"domainId\":\"" + domain + "\"}", 200).path("id").asText();
        String assignment = write("/sessions/" + session + "/assignments/next", "{}", 200)
            .path("assignment").path("assignmentId").asText();
        return new Fixture(id, domain, session, assignment);
    }

    @Test
    void concurrentRevisionAndDeletionCannotOverwriteEachOther() throws Exception {
        Fixture f = fixture();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CyclicBarrier(2);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (String action : java.util.List.of("delete", "revisions")) {
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    String body = "delete".equals(action) ? "{\"expectedVersion\":1,\"reason\":\"remove\"}"
                        : "{\"expectedVersion\":1,\"reason\":\"clarify\",\"changes\":{\"title\":\"revised\"}}";
                    return mvc.perform(post("/api/v1/questions/" + f.id + "/" + action)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andReturn().getResponse().getStatus();
                }));
            }
            assertThat(java.util.List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200, 409);
        }
    }
    private int events(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM question_lifecycle_event WHERE question_id=:id").param("id", id).query(Integer.class).single();
    }
    private int sessionCount(String id, String column) {
        return jdbc.sql("SELECT " + column + " FROM training_session WHERE id=:id").param("id", id).query(Integer.class).single();
    }
    private JsonNode read(String path) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1" + path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode write(String path, String body, int expected) throws Exception {
        return write(path, body, expected, UUID.randomUUID().toString());
    }
    private JsonNode write(String path, String body, int expected, String key) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1" + path).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expected))
            .andReturn().getResponse().getContentAsString());
    }
    private record Fixture(String id, String domain, String session, String assignment) {}
}
