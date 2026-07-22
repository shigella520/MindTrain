package io.github.shigella520.mindtrain.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shigella520.mindtrain.mcp.CoreClient.CoreClientException;
import io.github.shigella520.mindtrain.mcp.McpCompatibility.IncompatiblePluginException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class McpController {
    private final TrainerTools tools;
    private final ObjectMapper objectMapper;
    private final McpCompatibility compatibility;

    public McpController(TrainerTools tools, ObjectMapper objectMapper, McpCompatibility compatibility) {
        this.tools = tools;
        this.objectMapper = objectMapper;
        this.compatibility = compatibility;
    }

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> handle(@RequestBody JsonNode request,
                                           @RequestHeader(value = McpCompatibility.PLUGIN_VERSION_HEADER, required = false) String pluginVersion,
                                           @RequestHeader(value = McpCompatibility.CONTRACT_VERSION_HEADER, required = false) String contractVersion) {
        if (!request.has("id")) return ResponseEntity.accepted().build();
        JsonNode id = request.get("id");
        String method = request.path("method").asText();
        try {
            JsonNode result = switch (method) {
                case "initialize" -> initialize(request.path("params"));
                case "ping" -> objectMapper.createObjectNode();
                case "tools/list" -> objectMapper.createObjectNode().set("tools", tools.definitions());
                case "tools/call" -> {
                    compatibility.requireCompatibleToolClient(pluginVersion, contractVersion);
                    yield callTool(request.path("params"));
                }
                default -> throw new McpProtocolException(-32601, "Method not found: " + method);
            };
            return ResponseEntity.ok(response(id, result));
        } catch (McpProtocolException exception) {
            return ResponseEntity.ok(error(id, exception.code, exception.getMessage()));
        } catch (IncompatiblePluginException exception) {
            return ResponseEntity.ok(error(id, -32010, exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.ok(error(id, -32603, exception.getMessage()));
        }
    }

    private JsonNode initialize(JsonNode params) {
        JsonNode clientInfo = params.path("clientInfo");
        compatibility.requireCompatibleInitializeClient(clientInfo.path("name").asText(), clientInfo.path("version").asText());
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", params.path("protocolVersion").asText("2025-03-26"));
        result.set("capabilities", objectMapper.valueToTree(Map.of("tools", Map.of("listChanged", false))));
        result.set("serverInfo", objectMapper.valueToTree(Map.of(
            "name", "mindtrain-trainer-mcp", "version", compatibility.serverVersion())));
        result.set("_meta", objectMapper.valueToTree(Map.of("mindtrainCompatibility", Map.of(
            "contractVersion", McpCompatibility.CONTRACT_VERSION,
            "minimumPluginVersion", McpCompatibility.MINIMUM_PLUGIN_VERSION))));
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
