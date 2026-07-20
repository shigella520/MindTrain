package io.github.shigella520.mindtrain.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mindtrain")
public record MindTrainProperties(Security security, Scheduler scheduler, Reporting reporting, Training training) {
    public MindTrainProperties {
        if (reporting == null) reporting = new Reporting("Asia/Shanghai");
        if (training == null) training = new Training(24);
    }

    public record Security(boolean enabled, String bootstrapUserId, String bootstrapDisplayName, String bootstrapToken,
                           boolean publicDashboardEnabled) {}

    public record Scheduler(int reviewBudget, int newBudget, int backlogPauseThreshold, int overduePauseDays) {}

    public record Reporting(String timeZone) {
        public Reporting {
            if (timeZone == null || timeZone.isBlank()) timeZone = "Asia/Shanghai";
        }
    }

    public record Training(int pendingCandidateTtlHours) {
        public Training {
            if (pendingCandidateTtlHours < 1) pendingCandidateTtlHours = 24;
        }
    }
}
