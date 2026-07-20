package io.github.shigella520.mindtrain.core.identity;

import io.github.shigella520.mindtrain.core.config.MindTrainProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class IdentityService implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final MindTrainProperties properties;

    public IdentityService(JdbcClient jdbc, MindTrainProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        var security = properties.security();
        int updated = jdbc.sql("UPDATE app_user SET display_name = :name, token_hash = :hash WHERE id = :id")
            .param("id", security.bootstrapUserId())
            .param("name", security.bootstrapDisplayName())
            .param("hash", hash(security.bootstrapToken()))
            .update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO app_user(id, display_name, token_hash, created_at)
                    VALUES (:id, :name, :hash, :createdAt)
                    """)
                .param("id", security.bootstrapUserId()).param("name", security.bootstrapDisplayName())
                .param("hash", hash(security.bootstrapToken())).param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
        }
    }

    public String authenticate(String token) {
        String tokenHash = hash(token);
        return jdbc.sql("SELECT id FROM app_user WHERE token_hash = :hash")
            .param("hash", tokenHash)
            .query(String.class)
            .optional()
            .orElse(null);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
