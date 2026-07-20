package io.github.shigella520.mindtrain.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shigella520.mindtrain.mcp.CoreClient.CoreClientException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class McpController {
    private final TrainerTools tools;
    private final ObjectMapper objectMapper;

    public McpController(TrainerTools tools, ObjectMapper objectMapper) {
        this.tools = tools;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> handle(@RequestBody JsonNode request) {
        if (!request.has("id")) return ResponseEntity.accepted().build();
        JsonNode id = request.get("id");
        String method = request.path("method").asText();
        try {
            JsonNode result = switch (method) {
                case "initialize" -> initialize(request.path("params"));
                case "ping" -> objectMapper.createObjectNode();
                case "tools/list" -> objectMapper.createObjectNode().set("tools", tools.definitions());
                case "tools/call" -> callTool(request.path("params"));
                default -> throw new McpProtocolException(-32601, "Method not found: " + method);
            };
            return ResponseEntity.ok(response(id, result));
        } catch (McpProtocolException exception) {
            return ResponseEntity.ok(error(id, exception.code, exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.ok(error(id, -32603, exception.getMessage()));
        }
    }

    private JsonNode initialize(JsonNode params) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", params.path("protocolVersion").asText("2025-03-26"));
        result.set("capabilities", objectMapper.valueToTree(Map.of("tools", Map.of("listChanged", false))));
        result.set("serverInfo", objectMapper.valueToTree(Map.of("name", "mindtrain-trainer-mcp", "version", "0.1.0")));
        result.put("instructions", "Use tools to run MindTrain sessions. Never infer or expose answers before submit_choice_answer succeeds.");
        return result;
    }

    private JsonNode callTool(JsonNode params) {
        String name = params.path("name").asText();
        JsonNode arguments = params.has("arguments") ? params.path("arguments") : objectMapper.createObjectNode();
        ObjectNode result = objectMapper.createObjectNode();
        try {
            JsonNode value = tools.call(name, arguments);
            result.set("content", objectMapper.valueToTree(new Object[]{Map.of(
                "type", "text", "text", objectMapper.writeValueAsString(value))}));
            result.put("isError", false);
            result.set("structuredContent", value);
        } catch (CoreClientException exception) {
            result.set("content", objectMapper.valueToTree(new Object[]{Map.of(
                "type", "text", "text", "Training Core returned HTTP " + exception.status() + ": " + exception.getMessage())}));
            result.put("isError", true);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
        return result;
    }

    private ObjectNode response(JsonNode id, JsonNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("error", objectMapper.valueToTree(Map.of("code", code, "message", message == null ? "Internal error" : message)));
        return response;
    }

    private static class McpProtocolException extends RuntimeException {
        private final int code;

        McpProtocolException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
