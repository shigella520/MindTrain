package io.github.shigella520.mindtrain.core.question;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.mindtrain.core.api.ApiException;
import io.github.shigella520.mindtrain.core.identity.UserContext;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class QuestionQueryService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public QuestionQueryService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public QuestionPage list(String query, String domainId, String topicId, String type,
                             String learningState, String result, int requestedLimit, String cursor) {
        String userId = UserContext.requireUserId();
        int limit = Math.max(1, Math.min(requestedLimit <= 0 ? 20 : requestedLimit, 100));
        int offset = parseCursor(cursor);
        String normalized = lower(query);
        List<QuestionRow> rows = jdbc.sql("""
                SELECT q.id,q.domain_id,q.current_version,qv.type,qv.content_json,q.created_at,
                       rs.correct_count,rs.wrong_count,rs.last_answered_at,rs.next_review_at,
                       (SELECT a.correct FROM attempt a
                        WHERE a.user_id=:userId AND a.question_id=q.id
                        ORDER BY a.answered_at DESC LIMIT 1) AS latest_correct
                FROM question q
                JOIN question_version qv ON qv.question_id=q.id AND qv.version=q.current_version
                LEFT JOIN review_state rs ON rs.question_id=q.id AND rs.user_id=:userId
                WHERE q.user_id=:userId AND q.status='active'
                """).param("userId", userId).query((rs, rowNum) -> new QuestionRow(
                    rs.getString("id"), rs.getString("domain_id"), rs.getInt("current_version"),
                    rs.getString("type"), read(rs.getString("content_json")),
                    rs.getObject("created_at", OffsetDateTime.class), nullableInt(rs, "correct_count"),
                    nullableInt(rs, "wrong_count"), rs.getObject("last_answered_at", OffsetDateTime.class),
                    rs.getObject("next_review_at", OffsetDateTime.class),
                    nullableBoolean(rs, "latest_correct"))).list();

        List<QuestionSummary> matches = rows.stream()
            .filter(row -> blank(domainId) || domainId.equals(row.domainId()))
            .filter(row -> blank(topicId) || strings(row.content().path("topicIds")).contains(topicId))
            .filter(row -> blank(type) || type.equals(row.type()))
            .filter(row -> learningMatches(row, learningState))
            .filter(row -> resultMatches(row, result))
            .filter(row -> normalized.isEmpty() || searchable(row).contains(normalized))
            .map(this::summary)
            .sorted(Comparator.comparing(QuestionSummary::lastAnsweredAt,
                    Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(QuestionSummary::createdAt, Comparator.reverseOrder())
                .thenComparing(QuestionSummary::id))
            .toList();
        int from = Math.min(offset, matches.size());
        int to = Math.min(from + limit, matches.size());
        return new QuestionPage(matches.subList(from, to), matches.size(), to < matches.size() ? Integer.toString(to) : null);
    }

    public QuestionDetail detail(String questionId) {
        String userId = UserContext.requireUserId();
        QuestionRow row = current(questionId, userId);
        List<AttemptSummary> attempts = jdbc.sql("""
                SELECT id,question_version,selected_option_ids_json,correct,score,answered_at
                FROM attempt WHERE user_id=:userId AND question_id=:questionId
                ORDER BY answered_at DESC LIMIT 20
                """).param("userId", userId).param("questionId", questionId)
            .query((rs, rowNum) -> new AttemptSummary(rs.getString("id"), rs.getInt("question_version"),
                strings(read(rs.getString("selected_option_ids_json"))),
                rs.getBoolean("correct"), rs.getInt("score"),
                rs.getObject("answered_at", OffsetDateTime.class))).list();
        boolean answerVisible = attempts.stream().anyMatch(attempt -> attempt.questionVersion() == row.version());
        JsonNode presentation = row.content().deepCopy();
        if (!answerVisible && presentation.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) presentation).remove(List.of("correctOptionIds", "explanation"));
        }
        return new QuestionDetail(summary(row), answerVisible, presentation, attempts);
    }

    public RevisionContext revisionContext(String questionId, String assignmentId, int expectedVersion) {
        String userId = UserContext.requireUserId();
        QuestionRow row = current(questionId, userId);
        if (row.version() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "question_version_conflict",
                "Question current version is " + row.version() + ", not " + expectedVersion);
        }
        int matching = jdbc.sql("""
                SELECT COUNT(*) FROM assignment a JOIN training_session s ON s.id=a.session_id
                WHERE a.id=:assignmentId AND a.question_id=:questionId AND a.question_version=:version
                  AND a.status='pending' AND s.user_id=:userId
                """).param("assignmentId", assignmentId).param("questionId", questionId)
            .param("version", expectedVersion).param("userId", userId).query(Integer.class).single();
        if (matching == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "revision_assignment_invalid",
                "Revision context requires the current unanswered assignment");
        }
        return new RevisionContext(questionId, expectedVersion, assignmentId, row.content());
    }

    private QuestionRow current(String questionId, String userId) {
        return jdbc.sql("""
                SELECT q.id,q.domain_id,q.current_version,qv.type,qv.content_json,q.created_at,
                       rs.correct_count,rs.wrong_count,rs.last_answered_at,rs.next_review_at,
                       (SELECT a.correct FROM attempt a
                        WHERE a.user_id=:userId AND a.question_id=q.id
                        ORDER BY a.answered_at DESC LIMIT 1) AS latest_correct
                FROM question q JOIN question_version qv ON qv.question_id=q.id AND qv.version=q.current_version
                LEFT JOIN review_state rs ON rs.question_id=q.id AND rs.user_id=:userId
                WHERE q.id=:questionId AND q.user_id=:userId AND q.status='active'
                """).param("questionId", questionId).param("userId", userId)
            .query((rs, rowNum) -> new QuestionRow(rs.getString("id"), rs.getString("domain_id"),
                rs.getInt("current_version"), rs.getString("type"), read(rs.getString("content_json")),
                rs.getObject("created_at", OffsetDateTime.class), nullableInt(rs, "correct_count"),
                nullableInt(rs, "wrong_count"), rs.getObject("last_answered_at", OffsetDateTime.class),
                rs.getObject("next_review_at", OffsetDateTime.class),
                nullableBoolean(rs, "latest_correct")))
            .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "question_not_found", "Question was not found"));
    }

    private QuestionSummary summary(QuestionRow row) {
        int correct = row.correctCount() == null ? 0 : row.correctCount();
        int wrong = row.wrongCount() == null ? 0 : row.wrongCount();
        JsonNode content = row.content();
        return new QuestionSummary(row.id(), row.domainId(), row.version(), row.type(),
            content.path("title").asText(), content.path("stem").asText(),
            strings(content.path("topicIds")), content.path("difficulty").asInt(),
            content.path("sources"), correct + wrong, correct, wrong,
            row.lastAnsweredAt(), row.nextReviewAt(), row.createdAt());
    }

    private boolean learningMatches(QuestionRow row, String state) {
        if (blank(state) || "all".equals(state)) return true;
        boolean learned = row.correctCount() != null || row.wrongCount() != null;
        return "learned".equals(state) ? learned : "unseen".equals(state) && !learned;
    }

    private boolean resultMatches(QuestionRow row, String result) {
        if (blank(result) || "all".equals(result)) return true;
        Boolean latest = row.latestCorrect();
        return latest != null && (("correct".equals(result) && latest) || ("wrong".equals(result) && !latest));
    }

    private String searchable(QuestionRow row) {
        return lower(row.id() + " " + row.content().path("title").asText() + " " + row.content().path("stem").asText());
    }

    private Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Boolean nullableBoolean(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private int parseCursor(String cursor) {
        if (blank(cursor)) return 0;
        try { return Math.max(0, Integer.parseInt(cursor)); }
        catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "question_cursor_invalid", "cursor must be a non-negative integer");
        }
    }

    private JsonNode read(String value) {
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private List<String> strings(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(value -> result.add(value.asText()));
        return result;
    }

    private String lower(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private boolean blank(String value) { return value == null || value.isBlank(); }

    private record QuestionRow(String id, String domainId, int version, String type, JsonNode content,
                               OffsetDateTime createdAt, Integer correctCount, Integer wrongCount,
                               OffsetDateTime lastAnsweredAt, OffsetDateTime nextReviewAt,
                               Boolean latestCorrect) {}
    public record QuestionPage(List<QuestionSummary> items, int total, String nextCursor) {}
    public record QuestionSummary(String id, String domainId, int version, String type, String title, String stem,
                                  List<String> topicIds, int difficulty, JsonNode sources, int attemptCount,
                                  int correctCount, int wrongCount, OffsetDateTime lastAnsweredAt,
                                  OffsetDateTime nextReviewAt, OffsetDateTime createdAt) {}
    public record AttemptSummary(String id, int questionVersion, List<String> selectedOptionIds,
                                 boolean correct, int score,
                                 OffsetDateTime answeredAt) {}
    public record QuestionDetail(QuestionSummary summary, boolean answerVisible, JsonNode question,
                                 List<AttemptSummary> attempts) {}
    public record RevisionContext(String questionId, int version, String assignmentId, JsonNode question) {}
}
