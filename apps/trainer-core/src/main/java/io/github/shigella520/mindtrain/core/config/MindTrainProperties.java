package io.github.shigella520.mindtrain.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mindtrain")
public record MindTrainProperties(Security security, Scheduler scheduler) {
    public record Security(boolean enabled, String bootstrapUserId, String bootstrapDisplayName, String bootstrapToken) {}

    public record Scheduler(int reviewBudget, int newBudget, int backlogPauseThreshold, int overduePauseDays) {}
}
