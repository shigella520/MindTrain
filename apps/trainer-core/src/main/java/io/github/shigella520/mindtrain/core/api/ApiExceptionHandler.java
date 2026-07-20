package io.github.shigella520.mindtrain.core.api;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> handle(ApiException exception) {
        return ResponseEntity.status(exception.status())
            .body(Map.of("code", exception.code(), "message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> handleInvalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
            .body(Map.of("code", "invalid_request", "message", exception.getMessage()));
    }
}
