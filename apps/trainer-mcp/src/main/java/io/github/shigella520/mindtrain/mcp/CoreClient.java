package io.github.shigella520.mindtrain.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@Service
public class CoreClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public CoreClient(RestClient.Builder builder, ObjectMapper objectMapper,
                      @Value("${mindtrain.core.base-url}") String baseUrl,
                      @Value("${mindtrain.core.token}") String token) {
        this.restClient = builder.baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build();
        this.objectMapper = objectMapper;
    }

    public JsonNode get(String path) {
        try {
            return restClient.get().uri(path).retrieve().body(JsonNode.class);
        } catch (HttpStatusCodeException exception) {
            throw new CoreClientException(exception.getStatusCode().value(), exception.getResponseBodyAsString());
        }
    }

    public JsonNode post(String path, JsonNode body, String idempotencyKey) {
        try {
            var request = restClient.post().uri(path)
                .header("Idempotency-Key", idempotencyKey == null || idempotencyKey.isBlank()
                    ? UUID.randomUUID().toString() : idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON);
            return request.body(body == null ? objectMapper.createObjectNode() : body).retrieve().body(JsonNode.class);
        } catch (HttpStatusCodeException exception) {
            throw new CoreClientException(exception.getStatusCode().value(), exception.getResponseBodyAsString());
        }
    }

    public static class CoreClientException extends RuntimeException {
        private final int status;

        public CoreClientException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
