package io.github.shigella520.mindtrain.core.question;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.shigella520.mindtrain.core.api.ApiException;
import io.github.shigella520.mindtrain.core.identity.UserContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionService {
    private static final List<String> OPTION_IDS = List.of("A", "B", "C", "D");
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public QuestionService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public QuestionRecord get(String questionId, int version) {
        return jdbc.sql("""
                SELECT q.id, q.status, q.session_eligible_id, qv.version, qv.content_json
                FROM question q JOIN question_version qv ON qv.question_id = q.id
                WHERE q.id = :id AND qv.version = :version
                """)
            .param("id", questionId).param("version", version)
            .query((rs, rowNum) -> new QuestionRecord(rs.getString("id"), rs.getInt("version"),
                rs.getString("status"), rs.getString("session_eligible_id"), readTree(rs.getString("content_json"))))
            .optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "question_not_found", "Question was not found"));
    }

    public JsonNode sanitized(QuestionRecord question) {
        var result = objectMapper.createObjectNode();
        result.put("id", question.id());
        result.put("version", question.version());
        result.put("type", question.content().path("type").asText());
        result.put("title", question.content().path("title").asText());
        result.put("stem", question.content().path("stem").asText());
        result.set("options", question.content().path("options").deepCopy());
        result.set("topicIds", question.content().path("topicIds").deepCopy());
        result.put("difficulty", question.content().path("difficulty").asInt());
        return result;
    }

    @Transactional
    public CandidateResponse createCandidate(String sessionId, String topicId, JsonNode question,
                                             String attemptType, String parentAttemptId) {
        validateCandidate(question, topicId);
        String userId = UserContext.requireUserId();
        boolean sessionExists = jdbc.sql("""
                SELECT COUNT(*) FROM training_session
                WHERE id = :id AND user_id = :userId AND status = 'active'
                """)
            .param("id", sessionId).param("userId", userId).query(Integer.class).single() > 0;
        if (!sessionExists) {
            throw new ApiException(HttpStatus.CONFLICT, "session_not_active", "Candidate requires an active owning session");
        }
        String id = question.path("id").asText();
        int version = question.path("version").asInt(1);
        int existing = jdbc.sql("SELECT COUNT(*) FROM question WHERE id = :id")
            .param("id", id).query(Integer.class).single();
        if (existing > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "candidate_id_exists", "Question ID already exists");
        }
        var stored = question.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) stored).put("status", "candidate");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO question(id, status, current_version, session_eligible_id, created_at)
                VALUES (:id, 'candidate', :version, :sessionId, :createdAt)
                """)
            .param("id", id).param("version", version).param("sessionId", sessionId).param("createdAt", now).update();
        jdbc.sql("""
                INSERT INTO question_version(question_id, version, type, topic_ids_json, content_json, created_at)
                VALUES (:id, :version, :type, :topicIds, :content, :createdAt)
                """)
            .param("id", id).param("version", version).param("type", stored.path("type").asText())
            .param("topicIds", json(stored.path("topicIds"))).param("content", json(stored)).param("createdAt", now).update();
        String assignmentId = null;
        if ("follow_up".equals(attemptType)) {
            if (parentAttemptId == null || parentAttemptId.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "parent_attempt_required", "A follow-up candidate requires parentAttemptId");
            }
            int parentExists = jdbc.sql("SELECT COUNT(*) FROM attempt WHERE id = :attemptId AND session_id = :sessionId")
                .param("attemptId", parentAttemptId).param("sessionId", sessionId).query(Integer.class).single();
            if (parentExists == 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "parent_attempt_invalid", "Parent attempt does not belong to the session");
            }
            assignmentId = "assignment-" + java.util.UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO assignment(id, session_id, question_id, question_version, attempt_type,
                      parent_attempt_id, source_kind, status, created_at)
                    VALUES (:id, :sessionId, :questionId, :version, 'follow_up', :parentAttemptId,
                      'follow_up_candidate', 'pending', :createdAt)
                    """).param("id", assignmentId).param("sessionId", sessionId).param("questionId", id)
                .param("version", version).param("parentAttemptId", parentAttemptId).param("createdAt", now).update();
        }
        return new CandidateResponse(id, version, "candidate", sessionId, true, assignmentId);
    }

    public void validateCandidate(JsonNode question, String requiredTopicId) {
        List<String> missing = new ArrayList<>();
        for (String field : List.of("id", "version", "type", "title", "stem", "options", "correctOptionIds",
            "topicIds", "difficulty", "importance", "explanation", "sources", "createdBy", "promptVersion", "createdAt")) {
            if (!question.hasNonNull(field)) missing.add(field);
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "candidate_invalid", "Missing fields: " + String.join(", ", missing));
        }
        String type = question.path("type").asText();
        if (!Set.of("single_choice", "multiple_choice").contains(type)) {
            invalid("Only single_choice and multiple_choice are supported");
        }
        if (!question.path("options").isArray() || question.path("options").size() != 4) {
            invalid("Candidate must contain exactly four options");
        }
        Set<String> optionIds = new HashSet<>();
        question.path("options").forEach(option -> {
            if (!option.path("text").isTextual() || option.path("text").asText().isBlank()) invalid("Option text is required");
            optionIds.add(option.path("id").asText());
        });
        if (!optionIds.equals(Set.copyOf(OPTION_IDS))) invalid("Option IDs must be A, B, C and D");
        Set<String> correct = new HashSet<>();
        question.path("correctOptionIds").forEach(node -> correct.add(node.asText()));
        if (!optionIds.containsAll(correct) || (type.equals("single_choice") && correct.size() != 1)
            || (type.equals("multiple_choice") && (correct.size() < 2 || correct.size() > 3))) {
            invalid("Correct option count does not match the question type");
        }
        boolean hasTopic = false;
        for (JsonNode node : question.path("topicIds")) hasTopic |= requiredTopicId.equals(node.asText());
        if (!hasTopic) invalid("Candidate must include the requested topic");
        if (!question.path("sources").isArray() || question.path("sources").isEmpty()) invalid("At least one source is required");
        question.path("sources").forEach(source -> {
            if (!source.hasNonNull("url") || !source.hasNonNull("title") || !source.hasNonNull("accessedAt")) {
                invalid("Each source requires url, title and accessedAt");
            }
        });
        if (!question.path("explanation").has("optionAnalysis")
            || question.path("explanation").path("optionAnalysis").size() != 4) {
            invalid("Explanation requires four option analyses");
        }
    }

    public TopicContext topicContext(String topicId) {
        return jdbc.sql("SELECT name, content_json FROM topic WHERE id = :id")
            .param("id", topicId)
            .query((rs, rowNum) -> {
                JsonNode content = readTree(rs.getString("content_json"));
                return new TopicContext(topicId, rs.getString("name"), content.path("importance").asInt(),
                    jsonList(content.path("javaVersions")), jsonList(content.path("keywords")), jsonList(content.path("sourceRefs")));
            }).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "topic_not_found", "Topic was not found"));
    }

    public String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }

    public JsonNode readTree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public List<String> jsonList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node instanceof ArrayNode array) array.forEach(item -> values.add(item.asText()));
        return values;
    }

    private void invalid(String message) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "candidate_invalid", message);
    }

    public record QuestionRecord(String id, int version, String status, String sessionEligibleId, JsonNode content) {}
    public record CandidateResponse(String questionId, int version, String status, String sessionId,
                                    boolean usableInCurrentSession, String assignmentId) {}
    public record TopicContext(String id, String name, int importance, List<String> javaVersions,
                               List<String> keywords, List<String> sourceRefs) {}
}
