package io.github.shigella520.mindtrain.core.training;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shigella520.mindtrain.core.api.ApiException;
import io.github.shigella520.mindtrain.core.config.ApplicationSettingsService;
import io.github.shigella520.mindtrain.core.config.ApplicationSettingsService.TrainingSettings;
import io.github.shigella520.mindtrain.core.identity.UserContext;
import io.github.shigella520.mindtrain.core.question.QuestionService;
import io.github.shigella520.mindtrain.core.question.QuestionService.QuestionRecord;
import io.github.shigella520.mindtrain.core.scheduling.SchedulerProvider;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrainingService {
    private static final List<String> OPTION_IDS = List.of("A", "B", "C", "D");
    private static final Pattern COMPACT_OPTIONS = Pattern.compile("^[A-D](?:\\s*[,，/、和及]?\\s*[A-D])*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKED_OPTIONS = Pattern.compile("(?:我?选|选择|答案)\\s*[:：]?\\s*([A-D\\s,，/、和及]+)", Pattern.CASE_INSENSITIVE);
    private static final int MASTERY_LIST_LIMIT = 5;
    private static final int WEAK_MASTERY_THRESHOLD = 60;
    private static final int STRONG_MASTERY_THRESHOLD = 75;
    private static final int WEAK_MIN_ATTEMPTS = 2;
    private static final int STRONG_MIN_ATTEMPTS = 3;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final QuestionService questions;
    private final SchedulerProvider scheduler;
    private final ApplicationSettingsService applicationSettings;

    public TrainingService(JdbcClient jdbc, ObjectMapper objectMapper, QuestionService questions,
                           SchedulerProvider scheduler, ApplicationSettingsService applicationSettings) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.questions = questions;
        this.scheduler = scheduler;
        this.applicationSettings = applicationSettings;
    }

    @Transactional
    public SessionResponse createSession(CreateSessionRequest request) {
        String userId = UserContext.requireUserId();
        String id = "session-" + UUID.randomUUID();
        TrainingSettings settings = applicationSettings.get();
        int target = settings.questionCount();
        DomainSelection domain = resolveDomain(userId, request.domainId());
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
            .param("id", id).param("userId", userId).param("domain", domain.id()).param("provider", provider)
            .param("target", target).param("startedAt", now).update();
        return new SessionResponse(id, domain.id(), domain.name(), "active", target, 0, 0, provider, now, null);
    }

    @Transactional
    public NextAssignmentResponse nextAssignment(String sessionId) {
        String userId = UserContext.requireUserId();
        SessionRow session = session(sessionId, userId);
        if (!"active".equals(session.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "session_not_active", "Session is not active");
        }
        Optional<AssignmentRow> pending = jdbc.sql("""
                SELECT a.id, a.question_id, a.question_version, a.attempt_type, a.parent_attempt_id,
                       a.source_kind, a.created_at, q.domain_id
                FROM assignment a JOIN question q ON q.id=a.question_id
                WHERE a.session_id = :sessionId AND a.status = 'pending'
                ORDER BY a.created_at LIMIT 1
                """)
            .param("sessionId", sessionId).query(this::mapAssignment).optional();
        if (pending.isPresent()) {
            if (!session.domainId().equals(pending.get().domainId())) {
                throw new ApiException(HttpStatus.CONFLICT, "session_domain_invalid",
                    "Pending assignment belongs to a different training domain");
            }
            return assignmentResponse(pending.get());
        }
        if (session.completedMain() >= session.targetCount()) {
            return new NextAssignmentResponse("session_complete", null, null, null, null, null);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SchedulerProvider.Backlog backlog = scheduler.backlog(userId, session.domainId(), now);
        int scheduledReviews = jdbc.sql("""
                SELECT COUNT(*) FROM assignment WHERE session_id = :sessionId AND source_kind = 'review'
                """).param("sessionId", sessionId).query(Integer.class).single();
        TrainingSettings settings = applicationSettings.get();
        Optional<QuestionChoice> due = scheduledReviews < settings.reviewBudget() || backlog.newItemsPaused()
            ? selectDueQuestion(userId, session.domainId(), now) : Optional.empty();
        if (due.isPresent()) return createAssignment(session, due.get());

        int newAllowance = Math.min(settings.newBudget(), backlog.newItemAllowance());
        boolean shortageFill = !backlog.newItemsPaused() && session.completedMain() < session.targetCount();
        if (session.introducedNewCount() < newAllowance || shortageFill) {
            Optional<QuestionChoice> unseen = selectUnseenQuestion(userId, session.domainId(), sessionId);
            if (unseen.isPresent()) return createAssignment(session, unseen.get());

            String topicId = selectGenerationTopic(userId, session.domainId());
            QuestionService.TopicContext generationContext = questions.topicContext(topicId, session.domainId());
            QuestionService.GenerationProfile generationProfile =
                questions.generationProfile(userId, sessionId, generationContext);
            return new NextAssignmentResponse("generation_required", null, null,
                generationContext, generationProfile, Map.of(
                    "requiredStatus", "candidate",
                    "sessionId", sessionId,
                    "quotaMode", session.introducedNewCount() < newAllowance ? "planned_new" : "shortage_fill",
                    "neutralAnswerPrompt", "请回复选项字母，可用逗号分隔。"));
        }
        return new NextAssignmentResponse("no_available_items", null, null, null, null, Map.of(
            "reason", backlog.newItemsPaused() ? "new_items_paused_by_backlog" : "new_item_budget_exhausted",
            "dueCount", backlog.dueCount(), "newItemAllowance", backlog.newItemAllowance()));
    }

    @Transactional
    public AttemptResponse submitAttempt(String assignmentId, SubmitAttemptRequest request) {
        String userId = UserContext.requireUserId();
        AssignmentWithOwner assignment = jdbc.sql("""
                SELECT a.id, a.session_id, a.question_id, a.question_version, a.attempt_type, a.parent_attempt_id,
                       a.source_kind, a.status, s.user_id, s.domain_id AS session_domain_id,
                       q.domain_id AS question_domain_id
                FROM assignment a JOIN training_session s ON s.id = a.session_id
                JOIN question q ON q.id=a.question_id
                WHERE a.id = :id
                """)
            .param("id", assignmentId)
            .query((rs, rowNum) -> new AssignmentWithOwner(rs.getString("id"), rs.getString("session_id"),
                rs.getString("question_id"), rs.getInt("question_version"), rs.getString("attempt_type"),
                rs.getString("parent_attempt_id"), rs.getString("source_kind"), rs.getString("status"), rs.getString("user_id"),
                rs.getString("session_domain_id"), rs.getString("question_domain_id")))
            .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "assignment_not_found", "Assignment was not found"));
        if (!userId.equals(assignment.userId())) throw new ApiException(HttpStatus.NOT_FOUND, "assignment_not_found", "Assignment was not found");
        if (!assignment.sessionDomainId().equals(assignment.questionDomainId())) {
            throw new ApiException(HttpStatus.CONFLICT, "session_domain_invalid",
                "Assignment question belongs to a different training domain");
        }
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
        questions.activateCandidate(assignment.questionId(), assignment.sessionId());
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
    public RejectedCandidateResponse rejectCandidate(String assignmentId) {
        String userId = UserContext.requireUserId();
        CandidateAssignment candidate = candidateAssignment(assignmentId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "assignment_not_found", "Assignment was not found"));
        if (!userId.equals(candidate.userId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "assignment_not_found", "Assignment was not found");
        }
        validateRejectable(candidate);
        boolean allowanceRestored = deletePendingCandidate(candidate);
        return new RejectedCandidateResponse(candidate.assignmentId(), candidate.questionId(), candidate.sessionId(),
            true, true, allowanceRestored);
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
        cleanupPendingCandidates(sessionId, userId);
        JsonNode summary = sessionSummary(sessionId, userId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("UPDATE training_session SET status = 'completed', ended_at = :endedAt, summary_json = :summary WHERE id = :id")
            .param("endedAt", now).param("summary", stringify(summary)).param("id", sessionId).update();
        return new SessionResponse(session.id(), session.domainId(), session.domainName(), "completed",
            session.targetCount(), session.completedMain(), session.followUpCount(), session.schedulerProvider(),
            session.startedAt(), now);
    }

    public JsonNode overview() {
        String userId = UserContext.requireUserId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TrainingSettings settings = applicationSettings.get();
        ZoneId reportingZone = ZoneId.of(settings.reportingTimeZone());
        LocalDate reportingDate = now.atZoneSameInstant(reportingZone).toLocalDate();
        OffsetDateTime dayStart = reportingDate.atStartOfDay(reportingZone).toOffsetDateTime();
        OffsetDateTime dayEnd = reportingDate.plusDays(1).atStartOfDay(reportingZone).toOffsetDateTime();
        SchedulerProvider.Backlog backlog = scheduler.backlog(userId, null, now);
        Map<String, Object> stats = jdbc.sql("""
                SELECT COUNT(*) AS attempts,
                       COALESCE(SUM(CASE WHEN correct THEN 1 ELSE 0 END), 0) AS correct
                FROM attempt WHERE user_id = :userId
                """).param("userId", userId).query().singleRow();
        int attempts = ((Number) stats.get("attempts")).intValue();
        int correct = ((Number) stats.get("correct")).intValue();
        Map<String, Object> todayStats = jdbc.sql("""
                SELECT COUNT(*) AS completed,
                       COALESCE(SUM(CASE WHEN at.correct THEN 1 ELSE 0 END), 0) AS correct,
                       COALESCE(SUM(CASE WHEN a.source_kind = 'review' THEN 1 ELSE 0 END), 0) AS reviewed
                FROM attempt at
                JOIN assignment a ON a.id = at.assignment_id
                WHERE at.user_id = :userId AND a.attempt_type = 'main'
                  AND at.answered_at >= :dayStart AND at.answered_at < :dayEnd
                """)
            .param("userId", userId).param("dayStart", dayStart).param("dayEnd", dayEnd)
            .query().singleRow();
        int todayCompletedMainQuestions = number(todayStats.get("completed"));
        int todayCorrectMainQuestions = number(todayStats.get("correct"));
        int todayReviewCompleted = number(todayStats.get("reviewed"));
        int todayNewItemsIntroduced = jdbc.sql("""
                SELECT COUNT(*)
                FROM assignment a JOIN training_session s ON s.id=a.session_id
                WHERE s.user_id=:userId AND a.attempt_type='main'
                  AND a.source_kind IN ('new','candidate')
                  AND a.created_at >= :dayStart AND a.created_at < :dayEnd
                """).param("userId", userId).param("dayStart", dayStart).param("dayEnd", dayEnd)
            .query(Integer.class).single();
        int reviewBudget = settings.reviewBudget();
        int newBudget = settings.newBudget();
        int dailyTarget = reviewBudget + newBudget;
        int sessions = jdbc.sql("SELECT COUNT(*) FROM training_session WHERE user_id = :userId AND status = 'completed'")
            .param("userId", userId).query(Integer.class).single();
        int todayCompletedSessions = jdbc.sql("""
                SELECT COUNT(*) FROM training_session
                WHERE user_id=:userId AND status='completed'
                  AND ended_at >= :dayStart AND ended_at < :dayEnd
                """).param("userId", userId).param("dayStart", dayStart).param("dayEnd", dayEnd)
            .query(Integer.class).single();
        int activeQuestions = jdbc.sql("SELECT COUNT(*) FROM question WHERE user_id=:userId AND status='active'")
            .param("userId", userId).query(Integer.class).single();
        int reviewableQuestionCount = jdbc.sql("""
                SELECT COUNT(*) FROM question q
                WHERE q.user_id=:userId AND q.status='active'
                  AND EXISTS (SELECT 1 FROM review_state rs WHERE rs.user_id=:userId AND rs.question_id=q.id)
                """).param("userId", userId).query(Integer.class).single();
        int unseenQuestionCount = activeQuestions - reviewableQuestionCount;
        int candidates = jdbc.sql("SELECT COUNT(*) FROM question WHERE user_id=:userId AND status='candidate'")
            .param("userId", userId).query(Integer.class).single();
        int domainCount = jdbc.sql("SELECT COUNT(*) FROM knowledge_domain WHERE user_id=:userId")
            .param("userId", userId).query(Integer.class).single();
        int topicCount = jdbc.sql("""
                SELECT COUNT(*) FROM topic t
                WHERE EXISTS (
                    SELECT 1 FROM knowledge_domain d
                    WHERE d.user_id=:userId AND d.id=t.domain_id
                )
                """).param("userId", userId).query(Integer.class).single();
        MasteryHighlights masteryHighlights = masteryHighlights(userId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("attempts", attempts);
        response.put("correct", correct);
        response.put("accuracy", attempts == 0 ? 0.0 : (double) correct / attempts);
        response.put("todayCompletedMainQuestions", todayCompletedMainQuestions);
        response.put("todayCorrectMainQuestions", todayCorrectMainQuestions);
        response.put("todayAccuracy", todayCompletedMainQuestions == 0 ? 0.0
            : (double) todayCorrectMainQuestions / todayCompletedMainQuestions);
        response.put("todayReviewCompleted", todayReviewCompleted);
        response.put("todayNewItemsIntroduced", todayNewItemsIntroduced);
        response.put("todayCompletedSessions", todayCompletedSessions);
        response.put("dailyTarget", dailyTarget);
        response.put("reviewBudget", reviewBudget);
        response.put("newBudget", newBudget);
        response.put("reportingTimeZone", reportingZone.getId());
        response.put("completedSessions", sessions);
        response.put("dueCount", backlog.dueCount());
        if (backlog.oldestDueAt() != null) response.put("oldestDueAt", backlog.oldestDueAt().toString());
        response.put("newItemAllowance", backlog.newItemAllowance());
        response.put("newItemsPaused", backlog.newItemsPaused());
        response.put("schedulerStatus", schedulerStatus(backlog, todayCompletedMainQuestions, dailyTarget, now));
        String pauseReason = pauseReason(backlog, settings, now);
        if (pauseReason != null) response.put("newItemsPauseReason", pauseReason);
        response.put("schedulerProvider", scheduler.id());
        response.put("schedulerProviderName", scheduler.displayName());
        response.put("activeQuestions", activeQuestions);
        response.put("reviewableQuestionCount", reviewableQuestionCount);
        response.put("unseenQuestionCount", unseenQuestionCount);
        response.put("pendingGeneratedQuestions", candidates);
        response.put("knowledgeDomainCount", domainCount);
        response.put("knowledgeTopicCount", topicCount);
        response.set("weakTopics", objectMapper.valueToTree(masteryHighlights.weakTopics()));
        response.set("strongTopics", objectMapper.valueToTree(masteryHighlights.strongTopics()));
        response.set("insufficientEvidenceTopics", objectMapper.valueToTree(masteryHighlights.insufficientEvidenceTopics()));
        response.put("insufficientEvidenceTopicCount", masteryHighlights.insufficientEvidenceTopicCount());
        return response;
    }

    private String schedulerStatus(SchedulerProvider.Backlog backlog, int todayCompleted, int dailyTarget,
                                   OffsetDateTime now) {
        if (backlog.newItemsPaused()) return "paused";
        if (todayCompleted >= dailyTarget) return "completed";
        if (backlog.oldestDueAt() != null && backlog.oldestDueAt().isBefore(now.minusDays(1))) return "overdue";
        if (backlog.dueCount() > 0) return "due";
        return "healthy";
    }

    private String pauseReason(SchedulerProvider.Backlog backlog, TrainingSettings settings, OffsetDateTime now) {
        if (!backlog.newItemsPaused()) return null;
        boolean countExceeded = backlog.dueCount() > settings.backlogPauseThreshold();
        boolean ageExceeded = backlog.oldestDueAt() != null
            && backlog.oldestDueAt().isBefore(now.minusDays(settings.overduePauseDays()));
        if (countExceeded && ageExceeded) return "backlog_count_and_overdue_age";
        if (countExceeded) return "backlog_count";
        return "overdue_age";
    }

    private MasteryHighlights masteryHighlights(String userId) {
        Map<String, TopicPathNode> topicNodes = new HashMap<>();
        jdbc.sql("""
                SELECT t.id, t.parent_id, t.name, t.domain_id, d.name AS domain_name
                FROM topic t JOIN knowledge_domain d ON d.id=t.domain_id
                WHERE d.user_id=:userId
                """).param("userId", userId)
            .query((rs, rowNum) -> new TopicPathNode(rs.getString("id"), rs.getString("parent_id"),
                rs.getString("name"), rs.getString("domain_id"), rs.getString("domain_name")))
            .list().forEach(topic -> topicNodes.put(topic.id(), topic));

        List<MasteryInsight> insights = jdbc.sql("""
                SELECT tm.topic_id, tm.mastery_score, tm.correct_count, tm.wrong_count, tm.last_answered_at
                FROM topic_mastery tm
                JOIN topic t ON t.id=tm.topic_id
                JOIN knowledge_domain d ON d.id=t.domain_id AND d.user_id=:userId
                WHERE tm.user_id=:userId
                """).param("userId", userId)
            .query((rs, rowNum) -> {
                String topicId = rs.getString("topic_id");
                TopicPathNode topic = topicNodes.get(topicId);
                return new MasteryInsight(topicId, topic.name(), topic.domainId(), topic.domainName(),
                    topicPath(topicId, topicNodes), rs.getInt("mastery_score"), rs.getInt("correct_count"),
                    rs.getInt("wrong_count"), rs.getObject("last_answered_at", OffsetDateTime.class));
            }).list();

        List<Map<String, Object>> weakTopics = insights.stream()
            .filter(topic -> topic.attemptCount() >= WEAK_MIN_ATTEMPTS && topic.masteryScore() < WEAK_MASTERY_THRESHOLD)
            .sorted(Comparator.comparingInt(MasteryInsight::masteryScore)
                .thenComparing(Comparator.comparingInt(MasteryInsight::wrongCount).reversed())
                .thenComparing(Comparator.comparingInt(MasteryInsight::attemptCount).reversed()))
            .limit(MASTERY_LIST_LIMIT).map(this::masteryReportRow).toList();
        List<Map<String, Object>> strongTopics = insights.stream()
            .filter(topic -> topic.attemptCount() >= STRONG_MIN_ATTEMPTS && topic.masteryScore() >= STRONG_MASTERY_THRESHOLD)
            .sorted(Comparator.comparingInt(MasteryInsight::masteryScore).reversed()
                .thenComparing(Comparator.comparingInt(MasteryInsight::correctCount).reversed())
                .thenComparing(Comparator.comparingInt(MasteryInsight::attemptCount).reversed()))
            .limit(MASTERY_LIST_LIMIT).map(this::masteryReportRow).toList();
        List<MasteryInsight> insufficientEvidence = insights.stream()
            .filter(topic -> !isWeak(topic) && !isStrong(topic) && topic.attemptCount() < STRONG_MIN_ATTEMPTS)
            .sorted(Comparator.comparingInt(MasteryInsight::attemptCount).reversed()
                .thenComparing(MasteryInsight::lastAnsweredAt, Comparator.reverseOrder()))
            .toList();
        return new MasteryHighlights(weakTopics, strongTopics,
            insufficientEvidence.stream().limit(MASTERY_LIST_LIMIT).map(this::masteryReportRow).toList(),
            insufficientEvidence.size());
    }

    private boolean isWeak(MasteryInsight topic) {
        return topic.attemptCount() >= WEAK_MIN_ATTEMPTS && topic.masteryScore() < WEAK_MASTERY_THRESHOLD;
    }

    private boolean isStrong(MasteryInsight topic) {
        return topic.attemptCount() >= STRONG_MIN_ATTEMPTS && topic.masteryScore() >= STRONG_MASTERY_THRESHOLD;
    }

    private String topicPath(String topicId, Map<String, TopicPathNode> topicNodes) {
        List<String> names = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        TopicPathNode current = topicNodes.get(topicId);
        while (current != null && visited.add(current.id())) {
            names.add(0, current.name());
            current = current.parentId() == null ? null : topicNodes.get(current.parentId());
        }
        return String.join(" / ", names);
    }

    private Map<String, Object> masteryReportRow(MasteryInsight topic) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("topic_id", topic.topicId());
        row.put("name", topic.name());
        row.put("domain_id", topic.domainId());
        row.put("domain_name", topic.domainName());
        row.put("topic_path", topic.topicPath());
        row.put("mastery_score", topic.masteryScore());
        row.put("correct_count", topic.correctCount());
        row.put("wrong_count", topic.wrongCount());
        row.put("attempt_count", topic.attemptCount());
        row.put("last_answered_at", topic.lastAnsweredAt());
        return row;
    }

    public SchedulerProvider.Backlog backlog(String domainId) {
        String userId = UserContext.requireUserId();
        if (domainId != null && !domainId.isBlank()) requireDomain(userId, domainId);
        return scheduler.backlog(userId, blankNull(domainId), OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Scheduled(fixedDelay = 3600000)
    @Transactional
    public void cleanupExpiredPendingCandidates() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC)
            .minusHours(applicationSettings.get().pendingCandidateTtlHours());
        List<CandidateAssignment> expired = jdbc.sql("""
                SELECT a.id AS assignment_id, a.session_id, a.question_id, a.attempt_type, a.source_kind,
                       a.status AS assignment_status, s.user_id, q.status AS question_status,
                       q.session_eligible_id, a.created_at
                FROM assignment a
                JOIN training_session s ON s.id = a.session_id
                JOIN question q ON q.id = a.question_id
                WHERE a.status = 'pending' AND q.status = 'candidate' AND a.created_at < :cutoff
                """)
            .param("cutoff", cutoff).query(this::mapCandidateAssignment).list();
        expired.forEach(this::deletePendingCandidate);
        cleanupExpiredOrphanCandidates(cutoff);
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
        return assignmentResponse(new AssignmentRow(id, choice.questionId(), choice.version(), "main", null,
            choice.sourceKind(), now, session.domainId()));
    }

    private NextAssignmentResponse assignmentResponse(AssignmentRow assignment) {
        QuestionRecord question = questions.get(assignment.questionId(), assignment.version());
        AssignmentPresentation presentation = new AssignmentPresentation(assignment.id(), assignment.attemptType(),
            assignment.parentAttemptId(), assignment.sourceKind(), questions.sanitized(question),
            "请回复选项字母，可用逗号分隔。");
        return new NextAssignmentResponse("assignment", presentation, null, null, null, null);
    }

    private String selectGenerationTopic(String userId, String domainId) {
        return jdbc.sql("""
                SELECT t.id FROM topic t JOIN knowledge_domain d ON d.id=t.domain_id
                LEFT JOIN topic_mastery tm ON tm.topic_id = t.id AND tm.user_id = :userId
                WHERE d.user_id=:userId AND t.domain_id=:domainId AND t.kind = 'leaf'
                ORDER BY COALESCE(tm.mastery_score, 50) ASC,
                         CASE WHEN tm.topic_id IS NULL THEN 0 ELSE 1 END,
                         t.importance DESC, t.id LIMIT 1
                """)
            .param("userId", userId).param("domainId", domainId).query(String.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "training_domain_empty",
                "The selected training domain has no leaf knowledge points"));
    }

    private Optional<QuestionChoice> selectDueQuestion(String userId, String domainId, OffsetDateTime now) {
        List<ScheduledQuestion> choices = jdbc.sql("""
                SELECT q.id, q.current_version, q.status, rs.correct_count, rs.wrong_count, rs.next_review_at
                FROM review_state rs JOIN question q ON q.id = rs.question_id
                WHERE rs.user_id = :userId AND rs.next_review_at <= :now AND q.status = 'active'
                  AND q.user_id=:userId AND q.domain_id=:domainId
                """).param("userId", userId).param("domainId", domainId).param("now", now)
            .query((rs, rowNum) -> new ScheduledQuestion(rs.getString("id"), rs.getInt("current_version"),
                rs.getString("status"), rs.getInt("correct_count"), rs.getInt("wrong_count"),
                rs.getObject("next_review_at", OffsetDateTime.class))).list();
        return choices.stream().max(Comparator.comparingDouble(choice -> weightedScore(userId, choice, now, true)))
            .map(choice -> new QuestionChoice(choice.id(), choice.version(), "review"));
    }

    private Optional<QuestionChoice> selectUnseenQuestion(String userId, String domainId, String sessionId) {
        List<ScheduledQuestion> choices = jdbc.sql("""
                SELECT q.id, q.current_version, q.status
                FROM question q LEFT JOIN review_state rs ON rs.question_id = q.id AND rs.user_id = :userId
                WHERE rs.question_id IS NULL
                  AND q.user_id=:userId AND q.domain_id=:domainId
                  AND (q.status = 'active' OR q.session_eligible_id = :sessionId)
                """).param("userId", userId).param("domainId", domainId).param("sessionId", sessionId)
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

    private Optional<CandidateAssignment> candidateAssignment(String assignmentId) {
        return jdbc.sql("""
                SELECT a.id AS assignment_id, a.session_id, a.question_id, a.attempt_type, a.source_kind,
                       a.status AS assignment_status, s.user_id, q.status AS question_status,
                       q.session_eligible_id, a.created_at
                FROM assignment a
                JOIN training_session s ON s.id = a.session_id
                JOIN question q ON q.id = a.question_id
                WHERE a.id = :assignmentId
                """)
            .param("assignmentId", assignmentId).query(this::mapCandidateAssignment).optional();
    }

    private CandidateAssignment mapCandidateAssignment(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new CandidateAssignment(rs.getString("assignment_id"), rs.getString("session_id"),
            rs.getString("question_id"), rs.getString("attempt_type"), rs.getString("source_kind"),
            rs.getString("assignment_status"), rs.getString("user_id"), rs.getString("question_status"),
            rs.getString("session_eligible_id"), rs.getObject("created_at", OffsetDateTime.class));
    }

    private void validateRejectable(CandidateAssignment candidate) {
        if (!"pending".equals(candidate.assignmentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "candidate_already_answered",
                "Only an unanswered generated question can be rejected");
        }
        if (!"candidate".equals(candidate.questionStatus())
            || !candidate.sessionId().equals(candidate.sessionEligibleId())
            || !Set.of("candidate", "follow_up_candidate").contains(candidate.sourceKind())) {
            throw new ApiException(HttpStatus.CONFLICT, "question_not_rejectable",
                "Only a generated question owned by the current session can be rejected");
        }
        int attempts = jdbc.sql("SELECT COUNT(*) FROM attempt WHERE assignment_id = :assignmentId")
            .param("assignmentId", candidate.assignmentId()).query(Integer.class).single();
        if (attempts > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "candidate_already_answered",
                "Answered questions must be retained with their learning history");
        }
    }

    private boolean deletePendingCandidate(CandidateAssignment candidate) {
        jdbc.sql("DELETE FROM interaction_event WHERE assignment_id = :assignmentId")
            .param("assignmentId", candidate.assignmentId()).update();
        int deletedAssignments = jdbc.sql("""
                DELETE FROM assignment
                WHERE id = :assignmentId AND status = 'pending'
                """).param("assignmentId", candidate.assignmentId()).update();
        if (deletedAssignments == 0) return false;
        int remainingAssignments = jdbc.sql("SELECT COUNT(*) FROM assignment WHERE question_id = :questionId")
            .param("questionId", candidate.questionId()).query(Integer.class).single();
        if (remainingAssignments > 0) {
            throw new IllegalStateException("Generated candidate is referenced by another assignment");
        }
        jdbc.sql("DELETE FROM question_version WHERE question_id = :questionId")
            .param("questionId", candidate.questionId()).update();
        jdbc.sql("DELETE FROM question WHERE id = :questionId AND status = 'candidate'")
            .param("questionId", candidate.questionId()).update();
        boolean restoreAllowance = "main".equals(candidate.attemptType()) && "candidate".equals(candidate.sourceKind());
        if (restoreAllowance) {
            jdbc.sql("""
                    UPDATE training_session
                    SET introduced_new_count = GREATEST(introduced_new_count - 1, 0)
                    WHERE id = :sessionId
                    """).param("sessionId", candidate.sessionId()).update();
        }
        purgeDeletedAssignmentPresentations(candidate.userId(), candidate.assignmentId(), candidate.questionId());
        return restoreAllowance;
    }

    private void cleanupPendingCandidates(String sessionId, String userId) {
        List<CandidateAssignment> pending = jdbc.sql("""
                SELECT a.id AS assignment_id, a.session_id, a.question_id, a.attempt_type, a.source_kind,
                       a.status AS assignment_status, s.user_id, q.status AS question_status,
                       q.session_eligible_id, a.created_at
                FROM assignment a
                JOIN training_session s ON s.id = a.session_id
                JOIN question q ON q.id = a.question_id
                WHERE a.session_id = :sessionId AND s.user_id = :userId
                  AND a.status = 'pending' AND q.status = 'candidate'
                """)
            .param("sessionId", sessionId).param("userId", userId)
            .query(this::mapCandidateAssignment).list();
        pending.forEach(this::deletePendingCandidate);
        deleteOrphanCandidates(sessionId, userId, null);
    }

    private void cleanupExpiredOrphanCandidates(OffsetDateTime cutoff) {
        List<Map<String, Object>> sessions = jdbc.sql("""
                SELECT DISTINCT q.session_eligible_id AS session_id, s.user_id
                FROM question q JOIN training_session s ON s.id = q.session_eligible_id
                WHERE q.status = 'candidate' AND q.created_at < :cutoff
                  AND NOT EXISTS (SELECT 1 FROM assignment a WHERE a.question_id = q.id)
                """).param("cutoff", cutoff).query().listOfRows();
        sessions.forEach(row -> deleteOrphanCandidates((String) row.get("session_id"),
            (String) row.get("user_id"), cutoff));
    }

    private void deleteOrphanCandidates(String sessionId, String userId, OffsetDateTime cutoff) {
        String sql = """
                SELECT q.id FROM question q
                WHERE q.status = 'candidate' AND q.session_eligible_id = :sessionId
                  AND NOT EXISTS (SELECT 1 FROM assignment a WHERE a.question_id = q.id)
                """ + (cutoff == null ? "" : " AND q.created_at < :cutoff");
        var query = jdbc.sql(sql).param("sessionId", sessionId);
        if (cutoff != null) query = query.param("cutoff", cutoff);
        List<String> questionIds = query.query(String.class).list();
        for (String questionId : questionIds) {
            jdbc.sql("DELETE FROM question_version WHERE question_id = :questionId")
                .param("questionId", questionId).update();
            jdbc.sql("DELETE FROM question WHERE id = :questionId AND status = 'candidate'")
                .param("questionId", questionId).update();
            purgeDeletedAssignmentPresentations(userId, questionId);
        }
    }

    private void purgeDeletedAssignmentPresentations(String userId, String... identifiers) {
        List<Map<String, Object>> records = jdbc.sql("""
                SELECT idempotency_key, operation, response_json
                FROM idempotency_record
                WHERE user_id = :userId AND operation LIKE 'next-assignment:%'
                """).param("userId", userId).query().listOfRows();
        for (Map<String, Object> record : records) {
            String response = String.valueOf(record.get("response_json"));
            boolean matches = false;
            for (String identifier : identifiers) matches |= response.contains("\"" + identifier + "\"");
            if (matches) {
                jdbc.sql("""
                        DELETE FROM idempotency_record
                        WHERE user_id = :userId AND idempotency_key = :key AND operation = :operation
                        """).param("userId", userId).param("key", record.get("idempotency_key"))
                    .param("operation", record.get("operation")).update();
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

    private DomainSelection resolveDomain(String userId, String requestedDomainId) {
        if (requestedDomainId != null && !requestedDomainId.isBlank()) {
            DomainSelection selected = requireDomain(userId, requestedDomainId.trim());
            requireDomainTopics(userId, selected.id());
            return selected;
        }
        List<DomainSelection> domains = jdbc.sql("""
                SELECT id,name FROM knowledge_domain WHERE user_id=:userId ORDER BY sort_order,name,id
                """).param("userId", userId)
            .query((rs, rowNum) -> new DomainSelection(rs.getString("id"), rs.getString("name"))).list();
        if (domains.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "no_training_domains",
                "Create and confirm a training domain before starting training");
        }
        if (domains.size() > 1) {
            throw new ApiException(HttpStatus.CONFLICT, "training_domain_selection_required",
                "Multiple training domains are available; select one explicitly");
        }
        DomainSelection selected = domains.get(0);
        requireDomainTopics(userId, selected.id());
        return selected;
    }

    private DomainSelection requireDomain(String userId, String domainId) {
        return jdbc.sql("SELECT id,name FROM knowledge_domain WHERE user_id=:userId AND id=:domainId")
            .param("userId", userId).param("domainId", domainId)
            .query((rs, rowNum) -> new DomainSelection(rs.getString("id"), rs.getString("name")))
            .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "training_domain_not_found", "Training domain was not found"));
    }

    private void requireDomainTopics(String userId, String domainId) {
        int leafTopics = jdbc.sql("""
                SELECT COUNT(*) FROM topic t JOIN knowledge_domain d ON d.id=t.domain_id
                WHERE d.user_id=:userId AND t.domain_id=:domainId AND t.kind='leaf'
                """).param("userId", userId).param("domainId", domainId).query(Integer.class).single();
        if (leafTopics == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "training_domain_empty",
                "The selected training domain has no leaf knowledge points");
        }
    }

    private SessionRow session(String id, String userId) {
        SessionRow result = jdbc.sql("""
                SELECT s.id, s.domain_id, d.name AS domain_name, s.status, s.target_count,
                       s.completed_main, s.follow_up_count, s.introduced_new_count,
                       s.scheduler_provider, s.started_at, s.ended_at
                FROM training_session s LEFT JOIN knowledge_domain d
                  ON d.user_id=s.user_id AND d.id=s.domain_id
                WHERE s.id = :id AND s.user_id = :userId
                """).param("id", id).param("userId", userId)
            .query((rs, rowNum) -> new SessionRow(rs.getString("id"), rs.getString("domain_id"),
                rs.getString("domain_name"), rs.getString("status"), rs.getInt("target_count"),
                rs.getInt("completed_main"), rs.getInt("follow_up_count"), rs.getInt("introduced_new_count"),
                rs.getString("scheduler_provider"), rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class)))
            .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "session_not_found", "Session was not found"));
        if (result.domainName() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "session_domain_invalid",
                "The training session references a missing training domain; start a new session");
        }
        return result;
    }

    private AssignmentRow mapAssignment(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AssignmentRow(rs.getString("id"), rs.getString("question_id"), rs.getInt("question_version"),
            rs.getString("attempt_type"), rs.getString("parent_attempt_id"), rs.getString("source_kind"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getString("domain_id"));
    }

    private SessionResponse toResponse(SessionRow session) {
        return new SessionResponse(session.id(), session.domainId(), session.domainName(), session.status(),
            session.targetCount(), session.completedMain(), session.followUpCount(), session.schedulerProvider(),
            session.startedAt(), session.endedAt());
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

    private String blankNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record CreateSessionRequest(String domainId, String schedulerProvider) {}
    public record SubmitAttemptRequest(String answer) {}
    public record InteractionRequest(String assignmentId, String eventType, String content, String model, String promptVersion) {}
    public record SessionResponse(String id, String domainId, String domainName, String status, int targetCount,
                                  int completedMainQuestions, int followUpCount, String schedulerProvider,
                                  OffsetDateTime startedAt, OffsetDateTime endedAt) {}
    public record AssignmentPresentation(String assignmentId, String attemptType, String parentAttemptId,
                                         String sourceKind, JsonNode question, String answerPrompt) {}
    public record NextAssignmentResponse(String status, AssignmentPresentation assignment, String message,
                                         QuestionService.TopicContext generationContext,
                                         QuestionService.GenerationProfile generationProfile,
                                         Map<String, Object> details) {}
    public record AttemptResponse(String attemptId, String assignmentId, List<String> selectedOptionIds,
                                  List<String> correctOptionIds, boolean correct, int score, JsonNode explanation,
                                  JsonNode sources, OffsetDateTime answeredAt) {}
    public record InteractionResponse(String id, String sessionId, String assignmentId, OffsetDateTime createdAt,
                                      boolean consumedQuestion) {}
    public record RejectedCandidateResponse(String assignmentId, String questionId, String sessionId,
                                            boolean rejected, boolean physicallyDeleted,
                                            boolean newItemAllowanceRestored) {}
    private record SessionRow(String id, String domainId, String domainName, String status, int targetCount,
                              int completedMain, int followUpCount, int introducedNewCount,
                              String schedulerProvider, OffsetDateTime startedAt, OffsetDateTime endedAt) {}
    private record AssignmentRow(String id, String questionId, int version, String attemptType,
                                 String parentAttemptId, String sourceKind, OffsetDateTime createdAt, String domainId) {}
    private record AssignmentWithOwner(String id, String sessionId, String questionId, int questionVersion,
                                       String attemptType, String parentAttemptId, String sourceKind, String status,
                                       String userId, String sessionDomainId, String questionDomainId) {}
    private record CandidateAssignment(String assignmentId, String sessionId, String questionId,
                                       String attemptType, String sourceKind, String assignmentStatus,
                                       String userId, String questionStatus, String sessionEligibleId,
                                       OffsetDateTime createdAt) {}
    private record QuestionChoice(String questionId, int version, String sourceKind) {}
    private record ScheduledQuestion(String id, int version, String status, int correctCount,
                                     int wrongCount, OffsetDateTime nextReviewAt) {}
    private record DomainSelection(String id, String name) {}
    private record TopicPathNode(String id, String parentId, String name, String domainId, String domainName) {}
    private record MasteryInsight(String topicId, String name, String domainId, String domainName, String topicPath,
                                  int masteryScore, int correctCount, int wrongCount, OffsetDateTime lastAnsweredAt) {
        int attemptCount() {
            return correctCount + wrongCount;
        }
    }
    private record MasteryHighlights(List<Map<String, Object>> weakTopics,
                                     List<Map<String, Object>> strongTopics,
                                     List<Map<String, Object>> insufficientEvidenceTopics,
                                     int insufficientEvidenceTopicCount) {}
}
