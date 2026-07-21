package io.github.shigella520.mindtrain.core.scheduling;

import io.github.shigella520.mindtrain.core.config.ApplicationSettingsService;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class WeightedSchedulerProvider implements SchedulerProvider {
    private final JdbcClient jdbc;
    private final ApplicationSettingsService applicationSettings;

    public WeightedSchedulerProvider(JdbcClient jdbc, ApplicationSettingsService applicationSettings) {
        this.jdbc = jdbc;
        this.applicationSettings = applicationSettings;
    }

    @Override
    public String id() {
        return WEIGHTED_ID;
    }

    @Override
    public String displayName() {
        return WEIGHTED_DISPLAY_NAME;
    }

    @Override
    public Backlog backlog(String userId, OffsetDateTime now) {
        Map<String, Object> row = jdbc.sql("""
                SELECT COUNT(*) AS due_count, MIN(next_review_at) AS oldest_due
                FROM review_state rs JOIN question q ON q.id = rs.question_id
                WHERE rs.user_id = :userId AND rs.next_review_at <= :now AND q.status = 'active'
                """)
            .param("userId", userId).param("now", now).query().singleRow();
        int dueCount = ((Number) row.get("due_count")).intValue();
        Object oldestValue = row.get("oldest_due");
        OffsetDateTime oldest = oldestValue instanceof OffsetDateTime value ? value
            : oldestValue instanceof Timestamp timestamp ? timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC) : null;
        var settings = applicationSettings.get();
        boolean paused = dueCount > settings.backlogPauseThreshold()
            || (oldest != null && oldest.isBefore(now.minusDays(settings.overduePauseDays())));
        return new Backlog(dueCount, oldest, paused ? 0 : settings.newBudget(), paused);
    }
}
