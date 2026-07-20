package io.github.shigella520.mindtrain.core.scheduling;

import io.github.shigella520.mindtrain.core.config.MindTrainProperties;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class WeightedSchedulerProvider implements SchedulerProvider {
    private final JdbcClient jdbc;
    private final MindTrainProperties properties;

    public WeightedSchedulerProvider(JdbcClient jdbc, MindTrainProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public String id() {
        return "weighted";
    }

    @Override
    public Backlog backlog(String userId, OffsetDateTime now) {
        Map<String, Object> row = jdbc.sql("""
                SELECT COUNT(*) AS due_count, MIN(next_review_at) AS oldest_due
                FROM review_state WHERE user_id = :userId AND next_review_at <= :now
                """)
            .param("userId", userId).param("now", now).query().singleRow();
        int dueCount = ((Number) row.get("due_count")).intValue();
        Object oldestValue = row.get("oldest_due");
        OffsetDateTime oldest = oldestValue instanceof OffsetDateTime value ? value
            : oldestValue instanceof Timestamp timestamp ? timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC) : null;
        boolean paused = dueCount > properties.scheduler().backlogPauseThreshold()
            || (oldest != null && oldest.isBefore(now.minusDays(properties.scheduler().overduePauseDays())));
        return new Backlog(dueCount, oldest, paused ? 0 : properties.scheduler().newBudget(), paused);
    }
}
