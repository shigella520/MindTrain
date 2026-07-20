package io.github.shigella520.mindtrain.core.scheduling;

import java.time.OffsetDateTime;

public interface SchedulerProvider {
    String id();
    Backlog backlog(String userId, OffsetDateTime now);

    record Backlog(int dueCount, OffsetDateTime oldestDueAt, int newItemAllowance, boolean newItemsPaused) {}
}
