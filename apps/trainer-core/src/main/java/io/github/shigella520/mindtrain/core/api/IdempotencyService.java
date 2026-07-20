package io.github.shigella520.mindtrain.core.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shigella520.mindtrain.core.identity.UserContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public <T> T execute(String operation, String key, Class<T> type, Supplier<T> action) {
        if (key == null || key.isBlank()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "idempotency_key_required", "Idempotency-Key header is required");
        }
        String userId = UserContext.requireUserId();
        var existing = jdbc.sql("""
                SELECT response_json FROM idempotency_record
                WHERE user_id = :userId AND idempotency_key = :key AND operation = :operation
                """)
            .param("userId", userId).param("key", key).param("operation", operation)
            .query(String.class).optional();
        if (existing.isPresent()) {
            try {
                return objectMapper.readValue(existing.get(), type);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException(exception);
            }
        }
        T response = action.get();
        try {
            jdbc.sql("""
                    INSERT INTO idempotency_record(user_id, idempotency_key, operation, response_json, created_at)
                    VALUES (:userId, :key, :operation, :response, :createdAt)
                    """)
                .param("userId", userId).param("key", key).param("operation", operation)
                .param("response", objectMapper.writeValueAsString(response))
                .param("createdAt", OffsetDateTime.now(ZoneOffset.UTC)).update();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
        return response;
    }
}
