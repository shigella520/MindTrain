package io.github.shigella520.mindtrain.core.config;

import io.github.shigella520.mindtrain.core.api.ApiException;
import io.github.shigella520.mindtrain.core.identity.UserContext;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationSettingsService {
    private static final String DEFAULT_ID = "default";
    private final JdbcClient jdbc;

    public ApplicationSettingsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public TrainingSettings get() {
        return jdbc.sql("""
                SELECT question_count, new_budget, backlog_pause_threshold, overdue_pause_days,
                       pending_candidate_ttl_hours, reporting_time_zone, updated_at, updated_by
                FROM application_training_settings WHERE id = :id
                """)
            .param("id", DEFAULT_ID)
            .query((rs, rowNum) -> new TrainingSettings(
                rs.getInt("question_count"), rs.getInt("new_budget"),
                rs.getInt("question_count") - rs.getInt("new_budget"),
                rs.getInt("backlog_pause_threshold"), rs.getInt("overdue_pause_days"),
                rs.getInt("pending_candidate_ttl_hours"), rs.getString("reporting_time_zone"),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getString("updated_by")))
            .single();
    }

    @Transactional
    public TrainingSettings update(UpdateTrainingSettingsRequest request) {
        validate(request);
        String userId = UserContext.requireUserId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                UPDATE application_training_settings
                SET question_count = :questionCount,
                    new_budget = :newBudget,
                    backlog_pause_threshold = :backlogPauseThreshold,
                    overdue_pause_days = :overduePauseDays,
                    pending_candidate_ttl_hours = :pendingCandidateTtlHours,
                    reporting_time_zone = :reportingTimeZone,
                    updated_at = :updatedAt,
                    updated_by = :updatedBy
                WHERE id = :id
                """)
            .param("questionCount", request.questionCount())
            .param("newBudget", request.newBudget())
            .param("backlogPauseThreshold", request.backlogPauseThreshold())
            .param("overduePauseDays", request.overduePauseDays())
            .param("pendingCandidateTtlHours", request.pendingCandidateTtlHours())
            .param("reportingTimeZone", request.reportingTimeZone().trim())
            .param("updatedAt", now).param("updatedBy", userId).param("id", DEFAULT_ID)
            .update();
        return get();
    }

    private void validate(UpdateTrainingSettingsRequest request) {
        if (request.questionCount() < 1 || request.questionCount() > 100) {
            invalid("questionCount must be between 1 and 100");
        }
        if (request.newBudget() < 0 || request.newBudget() > request.questionCount()) {
            invalid("newBudget must be between 0 and questionCount");
        }
        if (request.backlogPauseThreshold() < 0 || request.backlogPauseThreshold() > 10000) {
            invalid("backlogPauseThreshold must be between 0 and 10000");
        }
        if (request.overduePauseDays() < 1 || request.overduePauseDays() > 365) {
            invalid("overduePauseDays must be between 1 and 365");
        }
        if (request.pendingCandidateTtlHours() < 1 || request.pendingCandidateTtlHours() > 8760) {
            invalid("pendingCandidateTtlHours must be between 1 and 8760");
        }
        String reportingTimeZone = request.reportingTimeZone() == null ? "" : request.reportingTimeZone().trim();
        if (reportingTimeZone.isEmpty()) {
            invalid("reportingTimeZone is required");
        }
        try {
            ZoneId.of(reportingTimeZone);
        } catch (Exception exception) {
            invalid("reportingTimeZone must be a valid IANA time zone");
        }
    }

    private void invalid(String message) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "training_settings_invalid", message);
    }

    public record TrainingSettings(int questionCount, int newBudget, int reviewBudget, int backlogPauseThreshold,
                                   int overduePauseDays, int pendingCandidateTtlHours,
                                   String reportingTimeZone, OffsetDateTime updatedAt, String updatedBy) {}

    public record UpdateTrainingSettingsRequest(int questionCount, int newBudget, int backlogPauseThreshold,
                                                int overduePauseDays, int pendingCandidateTtlHours,
                                                String reportingTimeZone) {}
}
