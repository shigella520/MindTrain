package io.github.shigella520.mindtrain.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mindtrain")
public record MindTrainProperties(Security security) {
    public record Security(boolean enabled, String bootstrapUserId, String bootstrapDisplayName, String bootstrapToken,
                           boolean publicDashboardEnabled) {}
}
