package io.github.shigella520.mindtrain.core.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shigella520.mindtrain.core.identity.IdentityService;
import io.github.shigella520.mindtrain.core.identity.UserContext;
import io.github.shigella520.mindtrain.core.question.QuestionService;
import io.github.shigella520.mindtrain.core.scheduling.SchedulerProvider;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrototypeImportService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final QuestionService questionService;

    public PrototypeImportService(JdbcClient jdbc, ObjectMapper objectMapper, QuestionService questionService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.questionService = questionService;
    }

    @Transactional
    public ImportResponse importPrototype(JsonNode request) {
        String userId = UserContext.requireUserId();
        boolean dryRun = request.path("dryRun").asBoolean(true);
        String requestHash = IdentityService.hash(canonical(request));
        String importId = "import-" + UUID.randomUUID();
        Counters counters = new Counters();
        Set<String> requestQuestionIds = collectQuestionIds(request);
        Set<String> requestSessionIds = collectSessionIds(request.path("sessions"));
        validateAndImportTopics(request.path("taxonomy"), dryRun, counters);
        validateAndImportQuestions(request.path("questions"), null, false, dryRun, counters);
        for (JsonNode wrapper : request.path("candidates")) {
            validateAndImportQuestions(wrapper.path("question"), wrapper.path("sessionId").asText(null), true, dryRun, counters);
        }
        validateAndImportSessions(request.path("sessions"), userId, dryRun, counters);
        validateAndImportAttempts(request.path("attempts"), userId, dryRun, counters, requestQuestionIds, requestSessionIds);
        validateAndImportMastery(request.path("mastery"), userId, dryRun, counters);

        ObjectNode report = objectMapper.createObjectNode();
        report.put("topicsSeen", counters.topicsSeen);
        report.put("topicsImported", counters.topicsImported);
        report.put("questionsSeen", counters.questionsSeen);
        report.put("questionsImported", counters.questionsImported);
        report.put("sessionsSeen", counters.sessionsSeen);
        report.put("sessionsImported", counters.sessionsImported);
        report.put("attemptsSeen", counters.attemptsSeen);
        report.put("attemptsImported", counters.attemptsImported);
        report.put("skipped", counters.skipped);
        report.put("conflicts", counters.conflicts);
        report.put("requestHash", requestHash);
        report.put("dryRun", dryRun);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO prototype_import(id, user_id, dry_run, request_hash, status, report_json, created_at)
                VALUES (:id, :userId, :dryRun, :hash, :status, :report, :createdAt)
                """)
            .param("id", importId).param("userId", userId).param("dryRun", dryRun).param("hash", requestHash)
            .param("status", counters.conflicts == 0 ? "completed" : "completed_with_conflicts")
            .param("report", canonical(report)).param("createdAt", now).update();
        return new ImportResponse(importId, dryRun, requestHash, report);
    }

    public ImportResponse get(String id) {
        String userId = UserContext.requireUserId();
        return jdbc.sql("""
                SELECT dry_run, request_hash, report_json FROM prototype_import
                WHERE id = :id AND user_id = :userId
                """).param("id", id).param("userId", userId)
            .query((rs, rowNum) -> new ImportResponse(id, rs.getBoolean("dry_run"), rs.getString("request_hash"),
                read(rs.getString("report_json"))))
            .optional().orElseThrow(() -> new IllegalArgumentException("Import report was not found"));
    }

    private void validateAndImportTopics(JsonNode taxonomy, boolean dryRun, Counters counters) {
        if (!taxonomy.isObject() || !taxonomy.path("topics").isArray()) return;
        for (JsonNode topic : taxonomy.path("topics")) {
            counters.topicsSeen++;
            String id = topic.path("id").asText();
            if (id.isBlank() || topic.path("name").asText().isBlank()) {
                counters.conflicts++;
                continue;
            }
            if (exists("topic", id)) {
                counters.skipped++;
                continue;
            }
            counters.topicsImported++;
            if (!dryRun) {
                jdbc.sql("""
                        INSERT INTO topic(id, domain_id, parent_id, name, kind, importance, content_json, created_at)
                        VALUES (:id, 'java-backend', :parentId, :name, :kind, :importance, :content, :createdAt)
                        """)
                    .param("id", id).param("parentId", topic.path("parentId").isNull() ? null : topic.path("parentId").asText(null))
                    .param("name", topic.path("name").asText()).param("kind", topic.path("kind").asText())
                    .param("importance", topic.path("importance").asInt(3)).param("content", canonical(topic))
                    .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC)).update();
            }
        }
    }

    private void validateAndImportQuestions(JsonNode value, String sessionId, boolean candidate,
                                            boolean dryRun, Counters counters) {
        if (value == null || value.isMissingNode() || value.isNull()) return;
        if (value.isArray()) {
            value.forEach(item -> validateAndImportQuestions(item, sessionId, candidate, dryRun, counters));
            return;
        }
        counters.questionsSeen++;
        String id = value.path("id").asText();
        int version = value.path("version").asInt(1);
        if (id.isBlank() || !value.path("options").isArray() || value.path("options").size() != 4) {
            counters.conflicts++;
            return;
        }
        if (candidate) {
            String topicId = value.path("topicIds").path(0).asText();
            try {
                questionService.validateCandidate(value, topicId);
            } catch (RuntimeException exception) {
                counters.conflicts++;
                return;
            }
        }
        int existingVersion = jdbc.sql("SELECT COUNT(*) FROM question_version WHERE question_id = :id AND version = :version")
            .param("id", id).param("version", version).query(Integer.class).single();
        if (existingVersion > 0) {
            counters.skipped++;
            return;
        }
        counters.questionsImported++;
        if (dryRun) return;
        String status = candidate ? "candidate" : value.path("status").asText("published");
        OffsetDateTime createdAt = parseDate(value.path("createdAt").asText(null));
        int existingQuestion = jdbc.sql("SELECT COUNT(*) FROM question WHERE id = :id").param("id", id).query(Integer.class).single();
        if (existingQuestion == 0) {
            jdbc.sql("""
                    INSERT INTO question(id, status, current_version, session_eligible_id, created_at)
                    VALUES (:id, :status, :version, :sessionId, :createdAt)
                    """).param("id", id).param("status", status).param("version", version)
                .param("sessionId", sessionId).param("createdAt", createdAt).update();
        } else {
            jdbc.sql("UPDATE question SET current_version = :version, status = :status WHERE id = :id")
                .param("version", version).param("status", status).param("id", id).update();
        }
        jdbc.sql("""
                INSERT INTO question_version(question_id, version, type, topic_ids_json, content_json, created_at)
                VALUES (:id, :version, :type, :topicIds, :content, :createdAt)
                """).param("id", id).param("version", version).param("type", value.path("type").asText())
            .param("topicIds", canonical(value.path("topicIds"))).param("content", canonical(value)).param("createdAt", createdAt).update();
    }

    private void validateAndImportSessions(JsonNode sessions, String userId, boolean dryRun, Counters counters) {
        if (!sessions.isArray()) return;
        for (JsonNode session : sessions) {
            counters.sessionsSeen++;
            String id = session.path("id").asText();
            if (id.isBlank()) {
                counters.conflicts++;
                continue;
            }
            if (exists("training_session", id)) {
                counters.skipped++;
                continue;
            }
            counters.sessionsImported++;
            if (!dryRun) {
                jdbc.sql("""
                        INSERT INTO training_session(id, user_id, domain_id, scheduler_provider, status, target_count,
                          completed_main, follow_up_count, introduced_new_count, started_at, ended_at, summary_json)
                        VALUES (:id, :userId, 'java-backend', :schedulerProvider, :status, :target, :completed,
                          :followUps, :introduced, :startedAt, :endedAt, :summary)
                        """).param("id", id).param("userId", userId).param("status", session.path("status").asText("completed"))
                    .param("schedulerProvider", SchedulerProvider.WEIGHTED_ID)
                    .param("target", session.path("target").path("questionCount").asInt(10))
                    .param("completed", session.path("completedMainQuestions").asInt())
                    .param("followUps", session.path("followUpCount").asInt()).param("introduced", session.path("completedMainQuestions").asInt())
                    .param("startedAt", parseDate(session.path("startedAt").asText(null)))
                    .param("endedAt", session.path("endedAt").isNull() ? null : parseDate(session.path("endedAt").asText(null)))
                    .param("summary", session.path("summary").isNull() ? null : canonical(session.path("summary"))).update();
            }
        }
    }

    private void validateAndImportAttempts(JsonNode attempts, String userId, boolean dryRun, Counters counters,
                                           Set<String> requestQuestionIds, Set<String> requestSessionIds) {
        if (!attempts.isArray()) return;
        for (JsonNode attempt : attempts) {
            counters.attemptsSeen++;
            String id = attempt.path("id").asText();
            if (id.isBlank() || exists("attempt", id)) {
                counters.skipped++;
                continue;
            }
            String sessionId = attempt.path("sessionId").asText();
            String questionId = attempt.path("questionId").asText();
            boolean sessionAvailable = exists("training_session", sessionId) || requestSessionIds.contains(sessionId);
            boolean questionAvailable = exists("question", questionId) || requestQuestionIds.contains(questionId);
            if (!sessionAvailable || !questionAvailable) {
                counters.conflicts++;
                continue;
            }
            counters.attemptsImported++;
            if (dryRun) continue;
            String assignmentId = "import-assignment-" + id;
            OffsetDateTime answeredAt = parseDate(attempt.path("answeredAt").asText(null));
            jdbc.sql("""
                    INSERT INTO assignment(id, session_id, question_id, question_version, attempt_type,
                      parent_attempt_id, source_kind, status, created_at, answered_at)
                    VALUES (:id, :sessionId, :questionId, :version, :attemptType,
                      :parentAttemptId, 'imported', 'answered', :createdAt, :answeredAt)
                    """).param("id", assignmentId).param("sessionId", sessionId).param("questionId", questionId)
                .param("version", attempt.path("questionVersion").asInt(1)).param("attemptType", attempt.path("attemptType").asText("main"))
                .param("parentAttemptId", attempt.path("parentAttemptId").asText(null)).param("createdAt", answeredAt)
                .param("answeredAt", answeredAt).update();
            jdbc.sql("""
                    INSERT INTO attempt(id, assignment_id, session_id, user_id, question_id, question_version,
                      raw_answer, selected_option_ids_json, correct_option_ids_json, correct, score, answered_at)
                    VALUES (:id, :assignmentId, :sessionId, :userId, :questionId, :version,
                      :raw, :selected, :correctOptions, :correct, :score, :answeredAt)
                    """).param("id", id).param("assignmentId", assignmentId).param("sessionId", sessionId)
                .param("userId", userId).param("questionId", questionId).param("version", attempt.path("questionVersion").asInt(1))
                .param("raw", attempt.path("rawAnswer").asText()).param("selected", canonical(attempt.path("selectedOptionIds")))
                .param("correctOptions", canonical(attempt.path("correctOptionIds"))).param("correct", attempt.path("correct").asBoolean())
                .param("score", attempt.path("score").asInt()).param("answeredAt", answeredAt).update();
            if (!attempt.path("correct").asBoolean()) {
                jdbc.sql("""
                        INSERT INTO mistake(id, attempt_id, user_id, question_id, resolved, recorded_at)
                        VALUES (:id, :attemptId, :userId, :questionId, false, :recordedAt)
                        """).param("id", "import-mistake-" + id).param("attemptId", id).param("userId", userId)
                    .param("questionId", questionId).param("recordedAt", answeredAt).update();
            }
            updateImportedReviewState(userId, questionId, attempt.path("correct").asBoolean(), answeredAt);
        }
    }

    private void updateImportedReviewState(String userId, String questionId, boolean correct, OffsetDateTime answeredAt) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT correct_count, wrong_count, consecutive_correct FROM review_state
                WHERE user_id = :userId AND question_id = :questionId
                """).param("userId", userId).param("questionId", questionId).query().listOfRows();
        Map<String, Object> current = rows.isEmpty() ? Map.of() : rows.get(0);
        int correctCount = number(current.get("correct_count")) + (correct ? 1 : 0);
        int wrongCount = number(current.get("wrong_count")) + (correct ? 0 : 1);
        int streak = correct ? number(current.get("consecutive_correct")) + 1 : 0;
        int interval = correct ? List.of(3, 7, 14, 30).get(Math.min(Math.max(streak - 1, 0), 3)) : 1;
        int updated = jdbc.sql("""
                UPDATE review_state SET correct_count = :correctCount, wrong_count = :wrongCount,
                  consecutive_correct = :streak, interval_days = :interval,
                  last_answered_at = :lastAnsweredAt, next_review_at = :nextReviewAt
                WHERE user_id = :userId AND question_id = :questionId
                """).param("correctCount", correctCount).param("wrongCount", wrongCount).param("streak", streak)
            .param("interval", interval).param("lastAnsweredAt", answeredAt).param("nextReviewAt", answeredAt.plusDays(interval))
            .param("userId", userId).param("questionId", questionId).update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO review_state(user_id, question_id, correct_count, wrong_count, consecutive_correct,
                      interval_days, last_answered_at, next_review_at)
                    VALUES (:userId, :questionId, :correctCount, :wrongCount, :streak, :interval, :lastAnsweredAt, :nextReviewAt)
                    """).param("userId", userId).param("questionId", questionId).param("correctCount", correctCount)
                .param("wrongCount", wrongCount).param("streak", streak).param("interval", interval)
                .param("lastAnsweredAt", answeredAt).param("nextReviewAt", answeredAt.plusDays(interval)).update();
        }
    }

    private void validateAndImportMastery(JsonNode mastery, String userId, boolean dryRun, Counters counters) {
        if (!mastery.path("topics").isObject() || dryRun) return;
        mastery.path("topics").fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            int exists = jdbc.sql("SELECT COUNT(*) FROM topic_mastery WHERE user_id = :userId AND topic_id = :topicId")
                .param("userId", userId).param("topicId", entry.getKey()).query(Integer.class).single();
            if (exists == 0) {
                jdbc.sql("""
                        INSERT INTO topic_mastery(user_id, topic_id, mastery_score, correct_count, wrong_count, last_answered_at)
                        VALUES (:userId, :topicId, :score, :correct, :wrong, :lastAnsweredAt)
                        """).param("userId", userId).param("topicId", entry.getKey()).param("score", value.path("masteryScore").asInt(50))
                    .param("correct", value.path("correctCount").asInt()).param("wrong", value.path("wrongCount").asInt())
                    .param("lastAnsweredAt", parseDate(value.path("lastAnsweredAt").asText(null))).update();
            }
        });
    }

    private boolean exists(String table, String id) {
        if (!List.of("topic", "question", "training_session", "attempt").contains(table)) throw new IllegalArgumentException("invalid table");
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE id = :id").param("id", id).query(Integer.class).single() > 0;
    }

    private Set<String> collectQuestionIds(JsonNode request) {
        Set<String> ids = new HashSet<>();
        collectQuestionIds(request.path("questions"), ids);
        request.path("candidates").forEach(wrapper -> collectQuestionIds(wrapper.path("question"), ids));
        return ids;
    }

    private void collectQuestionIds(JsonNode value, Set<String> ids) {
        if (value.isArray()) {
            value.forEach(item -> collectQuestionIds(item, ids));
        } else if (value.isObject() && value.hasNonNull("id")) {
            ids.add(value.path("id").asText());
        }
    }

    private Set<String> collectSessionIds(JsonNode sessions) {
        Set<String> ids = new HashSet<>();
        sessions.forEach(session -> {
            if (session.hasNonNull("id")) ids.add(session.path("id").asText());
        });
        return ids;
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private OffsetDateTime parseDate(String value) {
        return value == null || value.isBlank() ? OffsetDateTime.now(ZoneOffset.UTC) : OffsetDateTime.parse(value);
    }

    private String canonical(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class Counters {
        int topicsSeen;
        int topicsImported;
        int questionsSeen;
        int questionsImported;
        int sessionsSeen;
        int sessionsImported;
        int attemptsSeen;
        int attemptsImported;
        int skipped;
        int conflicts;
    }

    public record ImportResponse(String id, boolean dryRun, String requestHash, JsonNode report) {}
}
