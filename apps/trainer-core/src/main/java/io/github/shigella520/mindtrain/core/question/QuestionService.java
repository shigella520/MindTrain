package io.github.shigella520.mindtrain.core.question;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shigella520.mindtrain.core.api.ApiException;
import io.github.shigella520.mindtrain.core.identity.UserContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionService {
    private static final List<String> OPTION_IDS = List.of("A", "B", "C", "D");
    private static final Set<String> REVISION_FIELDS = Set.of(
        "type", "title", "stem", "options", "correctOptionIds", "topicIds",
        "difficulty", "importance", "applicableVersions", "javaVersions", "explanation", "sources"
    );
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public QuestionService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public QuestionRecord get(String questionId, int version) {
        return jdbc.sql("""
                SELECT q.id, q.user_id, q.domain_id, q.status, q.session_eligible_id, qv.version, qv.content_json
                FROM question q JOIN question_version qv ON qv.question_id = q.id
                WHERE q.id = :id AND q.user_id=:userId AND qv.version = :version
                """)
            .param("id", questionId).param("userId", UserContext.requireUserId()).param("version", version)
            .query((rs, rowNum) -> new QuestionRecord(rs.getString("id"), rs.getInt("version"),
                rs.getString("user_id"), rs.getString("domain_id"), rs.getString("status"),
                rs.getString("session_eligible_id"), readTree(rs.getString("content_json"))))
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

    public boolean activateCandidate(String questionId, String sessionId) {
        QuestionRecord current = current(questionId);
        if (!"candidate".equals(current.status())) return false;
        if (!sessionId.equals(current.sessionEligibleId())) {
            throw new ApiException(HttpStatus.CONFLICT, "candidate_session_mismatch",
                "Candidate does not belong to the assignment session");
        }
        ObjectNode activated = current.content().deepCopy();
        activated.put("status", "active");
        int updated = jdbc.sql("""
                UPDATE question SET status = 'active', session_eligible_id = NULL
                WHERE id = :id AND status = 'candidate' AND session_eligible_id = :sessionId
                """)
            .param("id", questionId).param("sessionId", sessionId).update();
        if (updated == 0) return false;
        jdbc.sql("""
                UPDATE question_version SET content_json = :content
                WHERE question_id = :id AND version = :version
                """)
            .param("content", json(activated)).param("id", questionId).param("version", current.version()).update();
        return true;
    }

    @Transactional
    public CandidateResponse createCandidate(String sessionId, String topicId, JsonNode question,
                                             String attemptType, String parentAttemptId) {
        String userId = UserContext.requireUserId();
        String sessionDomainId = jdbc.sql("""
                SELECT domain_id FROM training_session
                WHERE id = :id AND user_id = :userId AND status = 'active'
                """)
            .param("id", sessionId).param("userId", userId).query(String.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "session_not_active",
                "Candidate requires an active owning session"));
        String requestedTopicDomain = requireTopicDomain(userId, topicId);
        if (!sessionDomainId.equals(requestedTopicDomain)) {
            throw new ApiException(HttpStatus.CONFLICT, "candidate_domain_mismatch",
                "Candidate topic must belong to the session training domain");
        }
        if ("follow_up".equals(attemptType)) {
            validateCandidate(question, topicId);
        } else {
            validateCandidate(question, topicId,
                generationProfile(userId, sessionId, topicContext(topicId, sessionDomainId)));
        }
        validateQuestionTopics(userId, question.path("topicIds"), sessionDomainId, "candidate_domain_mismatch");
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
                INSERT INTO question(id, user_id, domain_id, status, current_version, session_eligible_id, created_at)
                VALUES (:id, :userId, :domainId, 'candidate', :version, :sessionId, :createdAt)
                """)
            .param("id", id).param("userId", userId).param("domainId", sessionDomainId)
            .param("version", version).param("sessionId", sessionId).param("createdAt", now).update();
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

    @Transactional
    public RevisionResponse reviseActive(String questionId, int expectedVersion, JsonNode changes,
                                         String reason, String sourceAssignmentId,
                                         String model, String promptVersion) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "revision_reason_required", "A revision reason is required");
        }
        if (changes == null || !changes.isObject() || changes.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "revision_changes_invalid", "Revision changes must be a non-empty object");
        }
        List<String> unsupported = new ArrayList<>();
        changes.fieldNames().forEachRemaining(field -> {
            if (!REVISION_FIELDS.contains(field)) unsupported.add(field);
        });
        if (!unsupported.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "revision_changes_invalid",
                "Unsupported revision fields: " + String.join(", ", unsupported));
        }

        QuestionRecord current = current(questionId);
        if (!"active".equals(current.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "question_not_active", "Only active questions can be revised");
        }
        if (current.version() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "question_version_conflict",
                "Question current version is " + current.version() + ", not " + expectedVersion);
        }

        String userId = UserContext.requireUserId();
        if (sourceAssignmentId != null && !sourceAssignmentId.isBlank()) {
            int sourceExists = jdbc.sql("""
                    SELECT COUNT(*)
                    FROM assignment a JOIN training_session s ON s.id = a.session_id
                    WHERE a.id = :assignmentId AND a.question_id = :questionId
                      AND a.question_version = :version AND s.user_id = :userId
                    """)
                .param("assignmentId", sourceAssignmentId).param("questionId", questionId)
                .param("version", expectedVersion).param("userId", userId)
                .query(Integer.class).single();
            if (sourceExists == 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "revision_assignment_invalid",
                    "Source assignment does not match the question version and current user");
            }
        }

        int nextVersion = expectedVersion + 1;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String revisionPromptVersion = promptVersion == null || promptVersion.isBlank()
            ? "manual-revision" : promptVersion;
        ObjectNode revised = current.content().deepCopy();
        changes.fields().forEachRemaining(entry -> revised.set(entry.getKey(), entry.getValue().deepCopy()));
        revised.put("id", questionId);
        revised.put("version", nextVersion);
        revised.put("status", "active");
        revised.put("createdBy", model == null || model.isBlank() ? "user" : "ai");
        if (model == null || model.isBlank()) revised.putNull("model");
        else revised.put("model", model);
        revised.put("promptVersion", revisionPromptVersion);
        revised.put("createdAt", now.toString());
        revised.put("reviewedAt", now.toString());
        validateQuestion(revised, null, "revision_invalid");
        validateQuestionTopics(userId, revised.path("topicIds"), current.domainId(), "question_topics_cross_domain");

        int updated = jdbc.sql("""
                UPDATE question SET current_version = :nextVersion
                WHERE id = :id AND status = 'active' AND current_version = :expectedVersion
                """)
            .param("nextVersion", nextVersion).param("id", questionId).param("expectedVersion", expectedVersion).update();
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "question_version_conflict", "Question was revised concurrently");
        }
        jdbc.sql("""
                INSERT INTO question_version(question_id, version, type, topic_ids_json, content_json, created_at)
                VALUES (:id, :version, :type, :topicIds, :content, :createdAt)
                """)
            .param("id", questionId).param("version", nextVersion).param("type", revised.path("type").asText())
            .param("topicIds", json(revised.path("topicIds"))).param("content", json(revised)).param("createdAt", now).update();
        String revisionId = "revision-" + java.util.UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO question_revision(id, question_id, from_version, to_version, user_id,
                  source_assignment_id, change_reason, model, prompt_version, created_at)
                VALUES (:id, :questionId, :fromVersion, :toVersion, :userId,
                  :sourceAssignmentId, :reason, :model, :promptVersion, :createdAt)
                """)
            .param("id", revisionId).param("questionId", questionId).param("fromVersion", expectedVersion)
            .param("toVersion", nextVersion).param("userId", userId).param("sourceAssignmentId", blankNull(sourceAssignmentId))
            .param("reason", reason.trim()).param("model", blankNull(model)).param("promptVersion", revisionPromptVersion)
            .param("createdAt", now).update();
        return new RevisionResponse(revisionId, questionId, expectedVersion, nextVersion, "active",
            reason.trim(), blankNull(sourceAssignmentId), now);
    }

    public void validateCandidate(JsonNode question, String requiredTopicId) {
        validateQuestion(question, requiredTopicId, "candidate_invalid");
    }

    public void validateCandidate(JsonNode question, String requiredTopicId, GenerationProfile profile) {
        validateQuestion(question, requiredTopicId, "candidate_invalid");
        if (!profile.questionType().equals(question.path("type").asText())) {
            invalid("candidate_profile_mismatch", "Candidate type must match generationProfile.questionType");
        }
        if (profile.difficulty() != question.path("difficulty").asInt()) {
            invalid("candidate_profile_mismatch", "Candidate difficulty must match generationProfile.difficulty");
        }
        if (profile.knowledgePoint().importance() != question.path("importance").asInt()) {
            invalid("candidate_profile_mismatch", "Candidate importance must match generationProfile.knowledgePoint.importance");
        }
    }

    private void validateQuestion(JsonNode question, String requiredTopicId, String errorCode) {
        List<String> missing = new ArrayList<>();
        for (String field : List.of("id", "version", "type", "title", "stem", "options", "correctOptionIds",
            "topicIds", "difficulty", "importance", "explanation", "sources", "createdBy", "promptVersion", "createdAt")) {
            if (!question.hasNonNull(field)) missing.add(field);
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, errorCode, "Missing fields: " + String.join(", ", missing));
        }
        if (question.path("version").asInt() < 1) {
            invalid(errorCode, "Question version must be positive");
        }
        if (!question.path("title").isTextual() || question.path("title").asText().isBlank()
            || !question.path("stem").isTextual() || question.path("stem").asText().isBlank()) {
            invalid(errorCode, "Question title and stem are required");
        }
        String type = question.path("type").asText();
        if (!Set.of("single_choice", "multiple_choice").contains(type)) {
            invalid(errorCode, "Only single_choice and multiple_choice are supported");
        }
        if (!question.path("options").isArray() || question.path("options").size() != 4) {
            invalid(errorCode, "Question must contain exactly four options");
        }
        Set<String> optionIds = new HashSet<>();
        question.path("options").forEach(option -> {
            if (!option.path("text").isTextual() || option.path("text").asText().isBlank()) invalid(errorCode, "Option text is required");
            optionIds.add(option.path("id").asText());
        });
        if (!optionIds.equals(Set.copyOf(OPTION_IDS))) invalid(errorCode, "Option IDs must be A, B, C and D");
        Set<String> correct = new HashSet<>();
        question.path("correctOptionIds").forEach(node -> correct.add(node.asText()));
        if (!optionIds.containsAll(correct) || (type.equals("single_choice") && correct.size() != 1)
            || (type.equals("multiple_choice") && (correct.size() < 2 || correct.size() > 3))) {
            invalid(errorCode, "Correct option count does not match the question type");
        }
        if (!question.path("topicIds").isArray() || question.path("topicIds").isEmpty()) {
            invalid(errorCode, "At least one topic is required");
        }
        if (requiredTopicId != null) {
            boolean hasTopic = false;
            for (JsonNode node : question.path("topicIds")) hasTopic |= requiredTopicId.equals(node.asText());
            if (!hasTopic) invalid(errorCode, "Candidate must include the requested topic");
        }
        if (!question.path("sources").isArray() || question.path("sources").isEmpty()) invalid(errorCode, "At least one source is required");
        question.path("sources").forEach(source -> {
            if (!source.hasNonNull("url") || !source.hasNonNull("title") || !source.hasNonNull("accessedAt")) {
                invalid(errorCode, "Each source requires url, title and accessedAt");
            }
            if ("local_reference".equals(source.path("sourceType").asText())) {
                if (!source.path("url").asText().startsWith("mindtrain-local://")
                    || source.path("libraryId").asText().isBlank()
                    || source.path("relativePath").asText().isBlank()
                    || !source.path("contentHash").asText().matches("[0-9a-f]{64}")) {
                    invalid(errorCode, "Local sources require a safe URI, libraryId, relativePath and SHA-256 contentHash");
                }
                int known = jdbc.sql("""
                        SELECT COUNT(*) FROM source_asset
                        WHERE user_id = :userId AND library_id = :libraryId
                          AND relative_path = :relativePath AND content_hash = :contentHash
                        """)
                    .param("userId", UserContext.requireUserId()).param("libraryId", source.path("libraryId").asText())
                    .param("relativePath", source.path("relativePath").asText())
                    .param("contentHash", source.path("contentHash").asText()).query(Integer.class).single();
                if (known == 0) invalid(errorCode, "Local source metadata has not been imported into the catalog");
            }
        });
        if (!question.path("explanation").has("optionAnalysis")
            || question.path("explanation").path("optionAnalysis").size() != 4) {
            invalid(errorCode, "Explanation requires four option analyses");
        }
    }

    private QuestionRecord current(String questionId) {
        return jdbc.sql("""
                SELECT q.id, q.user_id, q.domain_id, q.status, q.session_eligible_id,
                       q.current_version, qv.content_json
                FROM question q JOIN question_version qv
                  ON qv.question_id = q.id AND qv.version = q.current_version
                WHERE q.id = :id AND q.user_id=:userId
                """)
            .param("id", questionId).param("userId", UserContext.requireUserId())
            .query((rs, rowNum) -> new QuestionRecord(rs.getString("id"), rs.getInt("current_version"),
                rs.getString("user_id"), rs.getString("domain_id"), rs.getString("status"),
                rs.getString("session_eligible_id"), readTree(rs.getString("content_json"))))
            .optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "question_not_found", "Question was not found"));
    }

    public TopicContext topicContext(String topicId, String domainId) {
        return jdbc.sql("""
                SELECT t.name,t.content_json FROM topic t JOIN knowledge_domain d ON d.id=t.domain_id
                WHERE t.id=:id AND t.domain_id=:domainId AND d.user_id=:userId
                """)
            .param("id", topicId).param("domainId", domainId).param("userId", UserContext.requireUserId())
            .query((rs, rowNum) -> {
                JsonNode content = readTree(rs.getString("content_json"));
                JsonNode versions = content.has("applicableVersions")
                    ? content.path("applicableVersions") : content.path("javaVersions");
                return new TopicContext(topicId, rs.getString("name"), content.path("importance").asInt(),
                    jsonList(versions), jsonList(content.path("keywords")), jsonList(content.path("sourceRefs")));
            }).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "topic_not_found", "Topic was not found"));
    }

    private String requireTopicDomain(String userId, String topicId) {
        return jdbc.sql("""
                SELECT t.domain_id FROM topic t JOIN knowledge_domain d ON d.id=t.domain_id
                WHERE t.id=:topicId AND d.user_id=:userId
                """).param("topicId", topicId).param("userId", userId).query(String.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "topic_not_found", "Topic was not found"));
    }

    private void validateQuestionTopics(String userId, JsonNode topicIds, String expectedDomainId, String code) {
        Set<String> domains = new HashSet<>();
        for (JsonNode topicId : topicIds) domains.add(requireTopicDomain(userId, topicId.asText()));
        if (domains.size() != 1 || !domains.contains(expectedDomainId)) {
            throw new ApiException(HttpStatus.CONFLICT, code,
                "All question topics must belong to the same training domain as the question");
        }
    }

    public GenerationProfile generationProfile(String userId, String sessionId, TopicContext topic) {
        Map<String, Integer> typeCounts = jdbc.sql("""
                SELECT qv.type, COUNT(*) AS count
                FROM assignment a
                JOIN question_version qv
                  ON qv.question_id = a.question_id AND qv.version = a.question_version
                WHERE a.session_id = :sessionId AND a.attempt_type = 'main'
                GROUP BY qv.type
                """)
            .param("sessionId", sessionId)
            .query((rs, rowNum) -> Map.entry(rs.getString("type"), rs.getInt("count")))
            .list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        int singleCount = typeCounts.getOrDefault("single_choice", 0);
        int multipleCount = typeCounts.getOrDefault("multiple_choice", 0);
        String questionType = singleCount <= multipleCount ? "single_choice" : "multiple_choice";

        int mastery = jdbc.sql("""
                SELECT mastery_score FROM topic_mastery
                WHERE user_id = :userId AND topic_id = :topicId
                """)
            .param("userId", userId).param("topicId", topic.id())
            .query(Integer.class).optional().orElse(50);
        int difficulty = mastery < 40 ? 2 : mastery < 75 ? 3 : 4;
        List<JsonNode> sourceReferences = sourceReferences(userId, topic.sourceRefs());
        List<String> libraryIds = sourceReferences.stream().map(source -> source.path("libraryId").asText())
            .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toCollection(LinkedHashSet::new), ArrayList::new));
        KnowledgePoint knowledgePoint = new KnowledgePoint(topic.id(), topic.name(), topic.importance(),
            topic.applicableVersions(), topic.applicableVersions(), topic.keywords(), topic.sourceRefs(),
            sourceReferences, libraryIds);
        return new GenerationProfile(questionType, difficulty, knowledgePoint);
    }

    private List<JsonNode> sourceReferences(String userId, List<String> sourceIds) {
        List<JsonNode> sources = new ArrayList<>();
        for (String sourceId : sourceIds) {
            jdbc.sql("SELECT metadata_json FROM source_asset WHERE user_id=:userId AND id=:id")
                .param("userId", userId).param("id", sourceId).query(String.class).optional()
                .map(this::readTree).ifPresent(sources::add);
        }
        return sources;
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

    private String blankNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void invalid(String code, String message) {
        throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public record QuestionRecord(String id, int version, String userId, String domainId, String status,
                                 String sessionEligibleId, JsonNode content) {}
    public record CandidateResponse(String questionId, int version, String status, String sessionId,
                                    boolean usableInCurrentSession, String assignmentId) {}
    public record RevisionResponse(String revisionId, String questionId, int previousVersion, int version,
                                   String status, String reason, String sourceAssignmentId,
                                   OffsetDateTime revisedAt) {}
    public record TopicContext(String id, String name, int importance, List<String> applicableVersions,
                               List<String> keywords, List<String> sourceRefs) {}
    public record GenerationProfile(String questionType, int difficulty, KnowledgePoint knowledgePoint) {}
    public record KnowledgePoint(String topicId, String name, int importance, List<String> applicableVersions,
                                 List<String> javaVersions,
                                 List<String> keywords, List<String> sourceRefs,
                                 List<JsonNode> sourceReferences, List<String> referenceLibraryIds) {}
}
