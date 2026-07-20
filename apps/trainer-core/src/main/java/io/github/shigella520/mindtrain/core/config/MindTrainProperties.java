package io.github.shigella520.mindtrain.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mindtrain")
public record MindTrainProperties(Security security, Scheduler scheduler, Reporting reporting) {
    public MindTrainProperties {
        if (reporting == null) reporting = new Reporting("Asia/Shanghai");
    }

    public record Security(boolean enabled, String bootstrapUserId, String bootstrapDisplayName, String bootstrapToken,
                           boolean publicDashboardEnabled) {}

    public record Scheduler(int reviewBudget, int newBudget, int backlogPauseThreshold, int overduePauseDays) {}

    public record Reporting(String timeZone) {
        public Reporting {
            if (timeZone == null || timeZone.isBlank()) timeZone = "Asia/Shanghai";
        }
    }
}
