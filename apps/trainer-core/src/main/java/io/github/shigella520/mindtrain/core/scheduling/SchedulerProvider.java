package io.github.shigella520.mindtrain.core.scheduling;

import java.time.OffsetDateTime;

public interface SchedulerProvider {
    String WEIGHTED_ID = "weighted";
    String WEIGHTED_DISPLAY_NAME = "加权调度";

    String id();
    String displayName();
    Backlog backlog(String userId, OffsetDateTime now);

    record Backlog(int dueCount, OffsetDateTime oldestDueAt, int newItemAllowance, boolean newItemsPaused) {}
}
