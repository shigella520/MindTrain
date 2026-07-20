package io.github.shigella520.mindtrain.core.training;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shigella520.mindtrain.core.api.ApiException;
import io.github.shigella520.mindtrain.core.config.MindTrainProperties;
import io.github.shigella520.mindtrain.core.identity.UserContext;
import io.github.shigella520.mindtrain.core.question.QuestionService;
import io.github.shigella520.mindtrain.core.question.QuestionService.QuestionRecord;
import io.github.shigella520.mindtrain.core.scheduling.SchedulerProvider;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrainingService {
    private static final List<String> OPTION_IDS = List.of("A", "B", "C", "D");
    private static final Pattern COMPACT_OPTIONS = Pattern.compile("^[A-D](?:\\s*[,，/、和及]?\\s*[A-D])*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKED_OPTIONS = Pattern.compile("(?:我?选|选择|答案)\\s*[:：]?\\s*([A-D\\s,，/、和及]+)", Pattern.CASE_INSENSITIVE);

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final QuestionService questions;
    private final SchedulerProvider scheduler;
    private final MindTrainProperties properties;

    public TrainingService(JdbcClient jdbc, ObjectMapper objectMapper, QuestionService questions,
                           SchedulerProvider scheduler, MindTrainProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.questions = questions;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @Transactional
    public SessionResponse createSession(CreateSessionRequest request) {
        String userId = UserContext.requireUserId();
        String id = "session-" + UUID.randomUUID();
        int target = request.questionCount() == null ? 10 : request.questionCount();
        if (target < 1 || target > 100) throw new IllegalArgumentException("questionCount must be between 1 and 100");
        String domain = blankDefault(request.domainId(), "java-backend");
        String provider = blankDefault(request.schedulerProvider(), scheduler.id());
        if (!scheduler.id().equals(provider)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "scheduler_not_supported",
                "MVP 目前仅支持加权调度（provider ID: " + scheduler.id() + "）");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO training_session(id, user_id, domain_id, scheduler_provider, status, target_count,
                  completed_main, follow_up_count, introduced_new_count, started_at)
                VALUES (:id, :userId, :domain, :provider, 'active', :target, 0, 0, 0, :startedAt)
                """)
            .param("id", id).param("userId", userId).param("domain", domain).param("provider", provider)
            .param("target", target).param("startedAt", now).update();
        return new SessionResponse(id, "active", target, 0, 0, provider, now, null);
    }

    @Transactional
    public NextAssignmentResponse nextAssignment(String sessionId) {
        String userId = UserContext.requireUserId();
        SessionRow session = session(sessionId, userId);
        if (!"active".equals(session.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "session_not_active", "Session is not active");
        }
        Optional<AssignmentRow> pending = jdbc.sql("""
                SELECT id, question_id, question_version, attempt_type, parent_attempt_id, source_kind, created_at
                FROM assignment WHERE session_id = :sessionId AND status = 'pending'
                ORDER BY created_at LIMIT 1
                """)
            .param("sessionId", sessionId).query(this::mapAssignment).optional();
        if (pending.isPresent()) return assignmentResponse(pending.get());
        if (session.completedMain() >= session.targetCount()) {
            return new NextAssignmentResponse("session_complete", null, null, null, null);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SchedulerProvider.Backlog backlog = scheduler.backlog(userId, now);
        int scheduledReviews = jdbc.sql("""
                SELECT COUNT(*) FROM assignment WHERE session_id = :sessionId AND source_kind = 'review'
                """).param("sessionId", sessionId).query(Integer.class).single();
        Optional<QuestionChoice> due = scheduledReviews < properties.scheduler().reviewBudget() || backlog.newItemsPaused()
            ? selectDueQuestion(userId, sessionId, now) : Optional.empty();
        if (due.isPresent()) return createAssignment(session, due.get());

        int newAllowance = Math.min(properties.scheduler().newBudget(), backlog.newItemAllowance());
        if (session.introducedNewCount() < newAllowance) {
            Optional<QuestionChoice> unseen = selectUnseenQuestion(userId, sessionId);
            if (unseen.isPresent()) return createAssignment(session, unseen.get());

            String topicId = selectGenerationTopic(userId);
            return new NextAssignmentResponse("generation_required", null, null,
                questions.topicContext(topicId), Map.of(
                    "requiredStatus", "candidate",
                    "sessionId", sessionId,
                    "neutralAnswerPrompt", "请回复选项字母，可用逗号分隔。"));
        }
        return new NextAssignmentResponse("no_available_items", null, null, null, Map.of(
            "reason", backlog.newItemsPaused() ? "new_items_paused_by_backlog" : "new_item_budget_exhausted",
            "dueCount", backlog.dueCount(), "newItemAllowance", backlog.newItemAllowance()));
    }

    @Transactional
    public AttemptResponse submitAttempt(String assignmentId, SubmitAttemptRequest request) {
        String userId = UserContext.requireUserId();
        AssignmentWithOwner assignment = jdbc.sql("""
                SELECT a.id, a.session_id, a.question_id, a.question_version, a.attempt_type, a.parent_attempt_id,
                       a.source_kind, a.status, s.user_id
                FROM assignment a JOIN training_session s ON s.id = a.session_id
                WHERE a.id = :id
                """)
            .param("id", assignmentId)
            .query((rs, rowNum) -> new AssignmentWithOwner(rs.getString("id"), rs.getString("session_id"),
                rs.getString("question_id"), rs.getInt("question_version"), rs.getString("attempt_type"),
                rs.getString("parent_attempt_id"), rs.getString("source_kind"), rs.getString("status"), rs.getString("user_id")))
            .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "assignment_not_found", "Assignment was not found"));
        if (!userId.equals(assignment.userId())) throw new ApiException(HttpStatus.NOT_FOUND, "assignment_not_found", "Assignment was not found");
        if (!"pending".equals(assignment.status())) throw new ApiException(HttpStatus.CONFLICT, "assignment_already_answered", "Assignment is already answered");

        QuestionRecord question = questions.get(assignment.questionId(), assignment.questionVersion());
        List<String> selected = normalizeAnswer(request.answer(), question.content());
        List<String> correct = questions.jsonList(question.content().path("correctOptionIds"));
        boolean isCorrect = selected.equals(correct);
        int score = isCorrect ? 100 : 0;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String attemptId = "attempt-" + UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO attempt(id, assignment_id, session_id, user_id, question_id, question_version,
                  raw_answer, selected_option_ids_json, correct_option_ids_json, correct, score, answered_at)
                VALUES (:id, :assignmentId, :sessionId, :userId, :questionId, :version,
                  :raw, :selected, :correctOptions, :correct, :score, :answeredAt)
                """)
            .param("id", attemptId).param("assignmentId", assignmentId).param("sessionId", assignment.sessionId())
            .param("userId", userId).param("questionId", assignment.questionId()).param("version", assignment.questionVersion())
            .param("raw", request.answer()).param("selected", json(selected)).param("correctOptions", json(correct))
            .param("correct", isCorrect).param("score", score).param("answeredAt", now).update();
        jdbc.sql("UPDATE assignment SET status = 'answered', answered_at = :answeredAt WHERE id = :id")
            .param("answeredAt", now).param("id", assignmentId).update();
        String countColumn = "follow_up".equals(assignment.attemptType()) ? "follow_up_count" : "completed_main";
        jdbc.sql("UPDATE training_session SET " + countColumn + " = " + countColumn + " + 1 WHERE id = :id")
            .param("id", assignment.sessionId()).update();
        if (!isCorrect) {
            jdbc.sql("""
                    INSERT INTO mistake(id, attempt_id, user_id, question_id, resolved, recorded_at)
                    VALUES (:id, :attemptId, :userId, :questionId, false, :recordedAt)
                    """)
                .param("id", "mistake-" + UUID.randomUUID()).param("attemptId", attemptId).param("userId", userId)
                .param("questionId", assignment.questionId()).param("recordedAt", now).update();
        }
        updateReviewState(userId, question, isCorrect, now);
        updateTopicMastery(userId, question, score, isCorrect, now);
        return new AttemptResponse(attemptId, assignmentId, selected, correct, isCorrect, score,
            question.content().path("explanation"), question.content().path("sources"), now);
    }

    @Transactional
    public InteractionResponse recordInteraction(String sessionId, InteractionRequest request) {
        String userId = UserContext.requireUserId();
        session(sessionId, userId);
        if (request.content() == null || request.content().isBlank()) throw new IllegalArgumentException("content is required");
        String id = "interaction-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO interaction_event(id, session_id, assignment_id, user_id, event_type, content,
                  model, prompt_version, created_at)
                VALUES (:id, :sessionId, :assignmentId, :userId, :eventType, :content, :model, :promptVersion, :createdAt)
                """)
            .param("id", id).param("sessionId", sessionId).param("assignmentId", request.assignmentId())
            .param("userId", userId).param("eventType", blankDefault(request.eventType(), "clarification_question"))
            .param("content", request.content()).param("model", request.model()).param("promptVersion", request.promptVersion())
            .param("createdAt", now).update();
        return new InteractionResponse(id, sessionId, request.assignmentId(), now, false);
    }

    @Transactional
    public SessionResponse finishSession(String sessionId) {
        String userId = UserContext.requireUserId();
        SessionRow session = session(sessionId, userId);
        if (!"active".equals(session.status())) return toResponse(session);
        JsonNode summary = sessionSummary(sessionId, userId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("UPDATE training_session SET status = 'completed', ended_at = :endedAt, summary_json = :summary WHERE id = :id")
            .param("endedAt", now).param("summary", stringify(summary)).param("id", sessionId).update();
        return new SessionResponse(session.id(), "completed", session.targetCount(), session.completedMain(),
            session.followUpCount(), session.schedulerProvider(), session.startedAt(), now);
    }

    public JsonNode overview() {
        String userId = UserContext.requireUserId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SchedulerProvider.Backlog backlog = scheduler.backlog(userId, now);
        Map<String, Object> stats = jdbc.sql("""
                SELECT COUNT(*) AS attempts,
                       COALESCE(SUM(CASE WHEN correct THEN 1 ELSE 0 END), 0) AS correct
                FROM attempt WHERE user_id = :userId
                """).param("userId", userId).query().singleRow();
        int attempts = ((Number) stats.get("attempts")).intValue();
        int correct = ((Number) stats.get("correct")).intValue();
        int sessions = jdbc.sql("SELECT COUNT(*) FROM training_session WHERE user_id = :userId AND status = 'completed'")
            .param("userId", userId).query(Integer.class).single();
        int published = jdbc.sql("SELECT COUNT(*) FROM question WHERE status = 'published'").query(Integer.class).single();
        int candidates = jdbc.sql("SELECT COUNT(*) FROM question WHERE status = 'candidate'").query(Integer.class).single();
        List<Map<String, Object>> weakTopics = jdbc.sql("""
                SELECT tm.topic_id, t.name, tm.mastery_score, tm.correct_count, tm.wrong_count
                FROM topic_mastery tm LEFT JOIN topic t ON t.id = tm.topic_id
                WHERE tm.user_id = :userId ORDER BY tm.mastery_score, tm.wrong_count DESC LIMIT 5
                """).param("userId", userId).query().listOfRows();
        ObjectNode response = objectMapper.createObjectNode();
        response.put("attempts", attempts);
        response.put("correct", correct);
        response.put("accuracy", attempts == 0 ? 0.0 : (double) correct / attempts);
        response.put("completedSessions", sessions);
        response.put("dueCount", backlog.dueCount());
        if (backlog.oldestDueAt() != null) response.put("oldestDueAt", backlog.oldestDueAt().toString());
        response.put("newItemAllowance", backlog.newItemAllowance());
        response.put("newItemsPaused", backlog.newItemsPaused());
        response.put("schedulerProvider", scheduler.id());
        response.put("schedulerProviderName", scheduler.displayName());
        response.put("publishedQuestions", published);
        response.put("candidateQuestions", candidates);
        response.set("weakTopics", objectMapper.valueToTree(weakTopics));
        return response;
    }

    public SchedulerProvider.Backlog backlog() {
        return scheduler.backlog(UserContext.requireUserId(), OffsetDateTime.now(ZoneOffset.UTC));
    }

    private NextAssignmentResponse createAssignment(SessionRow session, QuestionChoice choice) {
        String id = "assignment-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO assignment(id, session_id, question_id, question_version, attempt_type,
                  source_kind, status, created_at)
                VALUES (:id, :sessionId, :questionId, :version, 'main', :sourceKind, 'pending', :createdAt)
                """)
            .param("id", id).param("sessionId", session.id()).param("questionId", choice.questionId())
            .param("version", choice.version()).param("sourceKind", choice.sourceKind()).param("createdAt", now).update();
        if (!"review".equals(choice.sourceKind())) {
            jdbc.sql("UPDATE training_session SET introduced_new_count = introduced_new_count + 1 WHERE id = :id")
                .param("id", session.id()).update();
        }
        return assignmentResponse(new AssignmentRow(id, choice.questionId(), choice.version(), "main", null, choice.sourceKind(), now));
    }

    private NextAssignmentResponse assignmentResponse(AssignmentRow assignment) {
        QuestionRecord question = questions.get(assignment.questionId(), assignment.version());
        AssignmentPresentation presentation = new AssignmentPresentation(assignment.id(), assignment.attemptType(),
            assignment.parentAttemptId(), assignment.sourceKind(), questions.sanitized(question),
            "请回复选项字母，可用逗号分隔。");
        return new NextAssignmentResponse("assignment", presentation, null, null, null);
    }

    private String selectGenerationTopic(String userId) {
        return jdbc.sql("""
                SELECT t.id FROM topic t
                LEFT JOIN topic_mastery tm ON tm.topic_id = t.id AND tm.user_id = :userId
                WHERE t.kind = 'leaf'
                ORDER BY COALESCE(tm.mastery_score, 50) ASC,
                         CASE WHEN tm.topic_id IS NULL THEN 0 ELSE 1 END,
                         t.importance DESC, t.id LIMIT 1
                """)
            .param("userId", userId).query(String.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "no_topics", "Import a knowledge taxonomy before training"));
    }

    private Optional<QuestionChoice> selectDueQuestion(String userId, String sessionId, OffsetDateTime now) {
        List<ScheduledQuestion> choices = jdbc.sql("""
                SELECT q.id, q.current_version, q.status, rs.correct_count, rs.wrong_count, rs.next_review_at
                FROM review_state rs JOIN question q ON q.id = rs.question_id
                WHERE rs.user_id = :userId AND rs.next_review_at <= :now
                  AND (q.status = 'published' OR q.session_eligible_id = :sessionId)
                """).param("userId", userId).param("now", now).param("sessionId", sessionId)
            .query((rs, rowNum) -> new ScheduledQuestion(rs.getString("id"), rs.getInt("current_version"),
                rs.getString("status"), rs.getInt("correct_count"), rs.getInt("wrong_count"),
                rs.getObject("next_review_at", OffsetDateTime.class))).list();
        return choices.stream().max(Comparator.comparingDouble(choice -> weightedScore(userId, choice, now, true)))
            .map(choice -> new QuestionChoice(choice.id(), choice.version(), "review"));
    }

    private Optional<QuestionChoice> selectUnseenQuestion(String userId, String sessionId) {
        List<ScheduledQuestion> choices = jdbc.sql("""
                SELECT q.id, q.current_version, q.status
                FROM question q LEFT JOIN review_state rs ON rs.question_id = q.id AND rs.user_id = :userId
                WHERE rs.question_id IS NULL
                  AND (q.status = 'published' OR q.session_eligible_id = :sessionId)
                """).param("userId", userId).param("sessionId", sessionId)
            .query((rs, rowNum) -> new ScheduledQuestion(rs.getString("id"), rs.getInt("current_version"),
                rs.getString("status"), 0, 0, null)).list();
        return choices.stream().max(Comparator.comparingDouble(choice -> weightedScore(userId, choice,
                OffsetDateTime.now(ZoneOffset.UTC), false)))
            .map(choice -> new QuestionChoice(choice.id(), choice.version(),
                "candidate".equals(choice.status()) ? "candidate" : "new"));
    }

    private double weightedScore(String userId, ScheduledQuestion choice, OffsetDateTime now, boolean due) {
        QuestionRecord question = questions.get(choice.id(), choice.version());
        List<String> topicIds = questions.jsonList(question.content().path("topicIds"));
        double masteryTotal = 0.0;
        int knownTopics = 0;
        for (String topicId : topicIds) {
            Optional<Integer> mastery = jdbc.sql("""
                    SELECT mastery_score FROM topic_mastery WHERE user_id = :userId AND topic_id = :topicId
                    """).param("userId", userId).param("topicId", topicId).query(Integer.class).optional();
            if (mastery.isPresent()) {
                masteryTotal += mastery.get();
                knownTopics++;
            }
        }
        double weakness = knownTopics == 0 ? 0.5 : 1.0 - (masteryTotal / knownTopics / 100.0);
        double uncovered = knownTopics == 0 ? 1.0 : 0.0;
        int total = choice.correctCount() + choice.wrongCount();
        double errorFrequency = total == 0 ? 0.0 : (double) choice.wrongCount() / total;
        double importance = question.content().path("importance").asDouble(3.0) / 5.0;
        double dueFactor = 0.0;
        if (due) {
            long overdueHours = Math.max(0, java.time.Duration.between(choice.nextReviewAt(), now).toHours());
            dueFactor = 1.0 + Math.min(1.0, overdueHours / 72.0);
        }
        double stableJitter = Math.floorMod(choice.id().hashCode(), 1000) / 1000.0 * 0.05;
        return 0.30 * dueFactor + 0.25 * weakness + 0.20 * errorFrequency
            + 0.15 * uncovered + 0.10 * importance + stableJitter;
    }

    private List<String> normalizeAnswer(String raw, JsonNode question) {
        if (raw == null || raw.isBlank()) invalidAnswer();
        String value = raw.trim().toUpperCase();
        String optionExpression = null;
        if (COMPACT_OPTIONS.matcher(value).matches()) optionExpression = value;
        Matcher marked = MARKED_OPTIONS.matcher(value);
        if (optionExpression == null && marked.find()) optionExpression = marked.group(1);
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (optionExpression != null) {
            for (char character : optionExpression.toCharArray()) {
                String option = String.valueOf(character);
                if (OPTION_IDS.contains(option)) selected.add(option);
            }
        }
        if (selected.isEmpty()) {
            question.path("options").forEach(option -> {
                if (raw.trim().equals(option.path("text").asText())) selected.add(option.path("id").asText());
            });
        }
        List<String> result = OPTION_IDS.stream().filter(selected::contains).toList();
        if (result.isEmpty() || ("single_choice".equals(question.path("type").asText()) && result.size() != 1)) invalidAnswer();
        return result;
    }

    private void invalidAnswer() {
        throw new ApiException(HttpStatus.BAD_REQUEST, "answer_unparseable", "请回复选项字母，可用逗号分隔。");
    }

    private void updateReviewState(String userId, QuestionRecord question, boolean correct, OffsetDateTime now) {
        Map<String, Object> current = firstRow(jdbc.sql("""
                SELECT correct_count, wrong_count, consecutive_correct FROM review_state
                WHERE user_id = :userId AND question_id = :questionId
                """).param("userId", userId).param("questionId", question.id()).query().listOfRows());
        int correctCount = number(current.get("correct_count")) + (correct ? 1 : 0);
        int wrongCount = number(current.get("wrong_count")) + (correct ? 0 : 1);
        int streak = correct ? number(current.get("consecutive_correct")) + 1 : 0;
        int interval = correct ? List.of(3, 7, 14, 30).get(Math.min(Math.max(streak - 1, 0), 3)) : 1;
        int updated = jdbc.sql("""
                UPDATE review_state SET correct_count = :correctCount, wrong_count = :wrongCount,
                  consecutive_correct = :streak, interval_days = :interval,
                  last_answered_at = :lastAnsweredAt, next_review_at = :nextReviewAt
                WHERE user_id = :userId AND question_id = :questionId
                """)
            .param("userId", userId).param("questionId", question.id()).param("correctCount", correctCount)
            .param("wrongCount", wrongCount).param("streak", streak).param("interval", interval)
            .param("lastAnsweredAt", now).param("nextReviewAt", now.plusDays(interval)).update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO review_state(user_id, question_id, correct_count, wrong_count, consecutive_correct,
                      interval_days, last_answered_at, next_review_at)
                    VALUES (:userId, :questionId, :correctCount, :wrongCount, :streak, :interval, :lastAnsweredAt, :nextReviewAt)
                    """)
                .param("userId", userId).param("questionId", question.id()).param("correctCount", correctCount)
                .param("wrongCount", wrongCount).param("streak", streak).param("interval", interval)
                .param("lastAnsweredAt", now).param("nextReviewAt", now.plusDays(interval)).update();
        }
    }

    private void updateTopicMastery(String userId, QuestionRecord question, int score, boolean correct, OffsetDateTime now) {
        for (String topicId : questions.jsonList(question.content().path("topicIds"))) {
            Map<String, Object> current = firstRow(jdbc.sql("""
                    SELECT mastery_score, correct_count, wrong_count FROM topic_mastery
                    WHERE user_id = :userId AND topic_id = :topicId
                    """).param("userId", userId).param("topicId", topicId).query().listOfRows());
            int oldScore = current.isEmpty() ? 50 : number(current.get("mastery_score"));
            int mastery = Math.round(oldScore * 0.7f + score * 0.3f);
            int correctCount = number(current.get("correct_count")) + (correct ? 1 : 0);
            int wrongCount = number(current.get("wrong_count")) + (correct ? 0 : 1);
            int updated = jdbc.sql("""
                    UPDATE topic_mastery SET mastery_score = :mastery, correct_count = :correctCount,
                      wrong_count = :wrongCount, last_answered_at = :lastAnsweredAt
                    WHERE user_id = :userId AND topic_id = :topicId
                    """)
                .param("userId", userId).param("topicId", topicId).param("mastery", mastery)
                .param("correctCount", correctCount).param("wrongCount", wrongCount).param("lastAnsweredAt", now).update();
            if (updated == 0) {
                jdbc.sql("""
                        INSERT INTO topic_mastery(user_id, topic_id, mastery_score, correct_count, wrong_count, last_answered_at)
                        VALUES (:userId, :topicId, :mastery, :correctCount, :wrongCount, :lastAnsweredAt)
                        """)
                    .param("userId", userId).param("topicId", topicId).param("mastery", mastery)
                    .param("correctCount", correctCount).param("wrongCount", wrongCount).param("lastAnsweredAt", now).update();
            }
        }
    }

    private JsonNode sessionSummary(String sessionId, String userId) {
        Map<String, Object> row = jdbc.sql("""
                SELECT COUNT(*) AS attempts, COALESCE(SUM(CASE WHEN correct THEN 1 ELSE 0 END), 0) AS correct
                FROM attempt WHERE session_id = :sessionId AND user_id = :userId
                """).param("sessionId", sessionId).param("userId", userId).query().singleRow();
        int attempts = number(row.get("attempts"));
        int correct = number(row.get("correct"));
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("totalAttempts", attempts);
        summary.put("correctAttempts", correct);
        summary.put("accuracy", attempts == 0 ? 0.0 : (double) correct / attempts);
        summary.set("wrongQuestionIds", objectMapper.valueToTree(jdbc.sql("""
                SELECT question_id FROM attempt WHERE session_id = :sessionId AND correct = false ORDER BY answered_at
                """).param("sessionId", sessionId).query(String.class).list()));
        summary.set("weakTopics", objectMapper.valueToTree(jdbc.sql("""
                SELECT tm.topic_id FROM topic_mastery tm WHERE tm.user_id = :userId
                ORDER BY mastery_score, wrong_count DESC LIMIT 5
                """).param("userId", userId).query(String.class).list()));
        return summary;
    }

    private SessionRow session(String id, String userId) {
        return jdbc.sql("""
                SELECT id, status, target_count, completed_main, follow_up_count, introduced_new_count,
                       scheduler_provider, started_at, ended_at
                FROM training_session WHERE id = :id AND user_id = :userId
                """).param("id", id).param("userId", userId)
            .query((rs, rowNum) -> new SessionRow(rs.getString("id"), rs.getString("status"), rs.getInt("target_count"),
                rs.getInt("completed_main"), rs.getInt("follow_up_count"), rs.getInt("introduced_new_count"),
                rs.getString("scheduler_provider"), rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class)))
            .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "session_not_found", "Session was not found"));
    }

    private AssignmentRow mapAssignment(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AssignmentRow(rs.getString("id"), rs.getString("question_id"), rs.getInt("question_version"),
            rs.getString("attempt_type"), rs.getString("parent_attempt_id"), rs.getString("source_kind"),
            rs.getObject("created_at", OffsetDateTime.class));
    }

    private SessionResponse toResponse(SessionRow session) {
        return new SessionResponse(session.id(), session.status(), session.targetCount(), session.completedMain(),
            session.followUpCount(), session.schedulerProvider(), session.startedAt(), session.endedAt());
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private Map<String, Object> firstRow(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private String json(List<String> values) {
        return stringify(objectMapper.valueToTree(values));
    }

    private String stringify(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record CreateSessionRequest(Integer questionCount, String domainId, String schedulerProvider) {}
    public record SubmitAttemptRequest(String answer) {}
    public record InteractionRequest(String assignmentId, String eventType, String content, String model, String promptVersion) {}
    public record SessionResponse(String id, String status, int targetCount, int completedMainQuestions,
                                  int followUpCount, String schedulerProvider, OffsetDateTime startedAt, OffsetDateTime endedAt) {}
    public record AssignmentPresentation(String assignmentId, String attemptType, String parentAttemptId,
                                         String sourceKind, JsonNode question, String answerPrompt) {}
    public record NextAssignmentResponse(String status, AssignmentPresentation assignment, String message,
                                         QuestionService.TopicContext generationContext, Map<String, Object> details) {}
    public record AttemptResponse(String attemptId, String assignmentId, List<String> selectedOptionIds,
                                  List<String> correctOptionIds, boolean correct, int score, JsonNode explanation,
                                  JsonNode sources, OffsetDateTime answeredAt) {}
    public record InteractionResponse(String id, String sessionId, String assignmentId, OffsetDateTime createdAt,
                                      boolean consumedQuestion) {}
    private record SessionRow(String id, String status, int targetCount, int completedMain, int followUpCount,
                              int introducedNewCount, String schedulerProvider, OffsetDateTime startedAt, OffsetDateTime endedAt) {}
    private record AssignmentRow(String id, String questionId, int version, String attemptType,
                                 String parentAttemptId, String sourceKind, OffsetDateTime createdAt) {}
    private record AssignmentWithOwner(String id, String sessionId, String questionId, int questionVersion,
                                       String attemptType, String parentAttemptId, String sourceKind, String status, String userId) {}
    private record QuestionChoice(String questionId, int version, String sourceKind) {}
    private record ScheduledQuestion(String id, int version, String status, int correctCount,
                                     int wrongCount, OffsetDateTime nextReviewAt) {}
}
