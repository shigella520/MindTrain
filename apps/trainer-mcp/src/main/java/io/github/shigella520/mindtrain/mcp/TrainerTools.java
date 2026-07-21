package io.github.shigella520.mindtrain.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class TrainerTools {
    private final CoreClient core;
    private final ObjectMapper objectMapper;

    public TrainerTools(CoreClient core, ObjectMapper objectMapper) {
        this.core = core;
        this.objectMapper = objectMapper;
    }

    public ArrayNode definitions() {
        ArrayNode tools = objectMapper.createArrayNode();
        tools.add(tool("create_training_session", "Create a MindTrain training session.", schema(Map.of(
            "domainId", string("Knowledge domain; defaults to java-backend"),
            "schedulerProvider", string("Scheduler provider ID; use weighted for 加权调度 in the MVP")
        ), List.of())));
        tools.add(tool("get_next_assignment", "Get the next safe-to-display question or a structured generationProfile.",
            schema(Map.of("sessionId", string("Active session ID")), List.of("sessionId"))));
        tools.add(tool("submit_choice_answer", "Submit a formal choice answer and receive deterministic grading.",
            schema(Map.of("assignmentId", string("Pending assignment ID"), "answer", string("User answer text")),
                List.of("assignmentId", "answer"))));
        tools.add(tool("reject_generated_question",
            "Reject an unanswered AI-generated question, physically delete it, restore its new-item allowance, and request a replacement next.",
            schema(Map.of("assignmentId", string("Pending generated assignment ID")), List.of("assignmentId"))));
        tools.add(tool("record_interaction", "Record a clarification, hint request, challenge or follow-up without consuming the question.",
            schema(Map.of(
                "sessionId", string("Active session ID"),
                "assignmentId", string("Related assignment ID"),
                "eventType", string("clarification_question, hint_requested, answer_challenge or explanation_followup"),
                "content", string("Interaction text"),
                "model", string("Model identifier when known"),
                "promptVersion", string("Prompt version when known")
            ), List.of("sessionId", "content"))));
        tools.add(tool("create_candidate_question", "Validate and save a candidate matching the issued generationProfile for its owning session only.",
            schema(Map.of(
                "sessionId", string("Owning active session ID"),
                "topicId", string("Requested topic ID"),
                "question", object("Complete candidate question object"),
                "attemptType", string("Use follow_up only for a deeper training question"),
                "parentAttemptId", string("Required when attemptType is follow_up")
            ), List.of("sessionId", "topicId", "question"))));
        tools.add(tool("revise_saved_question",
            "Create an immutable next version of an active saved question after explicit user approval.",
            schema(Map.of(
                "questionId", string("Active saved question ID"),
                "expectedVersion", integer("Version shown in the assignment; prevents stale overwrites"),
                "changes", object("Only changed fields, such as title, stem, options, explanation or sources"),
                "reason", string("Concise reason for the revision audit log"),
                "sourceAssignmentId", string("Assignment that exposed the issue when available"),
                "model", string("Model identifier when known"),
                "promptVersion", string("Prompt version when known")
            ), List.of("questionId", "expectedVersion", "changes", "reason"))));
        tools.add(tool("finish_training_session", "Finish a session and persist its summary.",
            schema(Map.of("sessionId", string("Session ID")), List.of("sessionId"))));
        tools.add(tool("get_learning_report", "Get learning, backlog and content overview metrics.", schema(Map.of(), List.of())));
        tools.add(tool("get_scheduler_backlog", "Get due backlog and current new-item allowance.", schema(Map.of(), List.of())));
        tools.add(tool("list_knowledge_domains", "List the authenticated user's training domains and catalog coverage.",
            schema(Map.of("query", string("Optional domain name or description filter")), List.of())));
        tools.add(tool("get_knowledge_catalog_tree", "Get all root knowledge points and descendants for one domain.",
            schema(Map.of("domainId", string("Knowledge domain ID")), List.of("domainId"))));
        tools.add(tool("search_knowledge_topics", "Search knowledge points and return their domain and ancestor path.",
            schema(Map.of(
                "query", string("Required topic name, description or keyword query"),
                "domainId", string("Optional domain filter"),
                "limit", integer("Optional page size from 1 to 100"),
                "cursor", string("Optional cursor returned by a prior search")
            ), List.of("query"))));
        tools.add(tool("get_knowledge_topic", "Get one knowledge point with children, relations, sources and coverage.",
            schema(Map.of("topicId", string("Knowledge point ID")), List.of("topicId"))));
        tools.add(tool("preview_training_domain",
            "Validate and save an immutable training-domain draft from local references or AI dialogue without changing active data.",
            schema(Map.of(
                "originType", string("local_reference or ai_dialogue"),
                "libraryId", string("Required only for local_reference; never send an absolute path"),
                "context", object("Learning goal, audience, scope, model and prompt version metadata"),
                "proposal", object("Exactly one domain target with topics, relations and source metadata")
            ), List.of("originType", "proposal"))));
        tools.add(tool("get_training_domain_draft", "Get a training-domain draft, validation, warnings and persistence diff.",
            schema(Map.of("draftId", string("Training-domain draft ID")), List.of("draftId"))));
        tools.add(tool("confirm_training_domain", "Save and enable a complete draft after explicit user confirmation.",
            schema(Map.of(
                "draftId", string("Training-domain draft ID"),
                "proposalHash", string("Exact hash returned by preview")
            ), List.of("draftId", "proposalHash"))));
        tools.add(tool("discard_training_domain_draft", "Discard a draft without changing active catalog data.",
            schema(Map.of("draftId", string("Training-domain draft ID")), List.of("draftId"))));
        tools.add(tool("preview_knowledge_catalog_import",
            "Compatibility alias for preview_training_domain for local-reference clients.",
            schema(Map.of(
                "libraryId", string("Local reference library ID; no absolute path"),
                "proposal", object("Domains, topics, relations and source metadata proposal")
            ), List.of("libraryId", "proposal"))));
        tools.add(tool("get_knowledge_catalog_import", "Get a training-domain preview, validation result and persistence diff.",
            schema(Map.of("importId", string("Catalog import ID")), List.of("importId"))));
        tools.add(tool("apply_knowledge_catalog_import",
            "Save and enable a previously previewed training domain after explicit user confirmation.",
            schema(Map.of(
                "importId", string("Catalog import ID"),
                "proposalHash", string("Exact hash returned by preview")
            ), List.of("importId", "proposalHash"))));
        tools.add(tool("reject_knowledge_catalog_import", "Discard a training-domain proposal without changing active data.",
            schema(Map.of("importId", string("Catalog import ID")), List.of("importId"))));
        return tools;
    }

    public JsonNode call(String name, JsonNode arguments) {
        String key = arguments.path("idempotencyKey").asText(null);
        return switch (name) {
            case "create_training_session" -> core.post("/api/v1/sessions", arguments, key);
            case "get_next_assignment" -> core.post("/api/v1/sessions/" + required(arguments, "sessionId") + "/assignments/next",
                objectMapper.createObjectNode(), key);
            case "submit_choice_answer" -> core.post("/api/v1/assignments/" + required(arguments, "assignmentId") + "/attempts",
                objectMapper.createObjectNode().put("answer", required(arguments, "answer")), key);
            case "reject_generated_question" -> core.post(
                "/api/v1/assignments/" + required(arguments, "assignmentId") + "/reject",
                objectMapper.createObjectNode(), key);
            case "record_interaction" -> core.post("/api/v1/sessions/" + required(arguments, "sessionId") + "/interactions",
                without(arguments, "sessionId", "idempotencyKey"), key);
            case "create_candidate_question" -> core.post("/api/v1/candidates", arguments, key);
            case "revise_saved_question" -> core.post(
                "/api/v1/questions/" + required(arguments, "questionId") + "/revisions",
                without(arguments, "questionId", "idempotencyKey"), key);
            case "finish_training_session" -> core.post("/api/v1/sessions/" + required(arguments, "sessionId") + "/finish",
                objectMapper.createObjectNode(), key);
            case "get_learning_report" -> core.get("/api/v1/reports/overview");
            case "get_scheduler_backlog" -> core.get("/api/v1/schedulers/backlog");
            case "list_knowledge_domains" -> core.get("/api/v1/catalog/domains" + query(arguments, "query", "q"));
            case "get_knowledge_catalog_tree" -> core.get(
                "/api/v1/catalog/domains/" + encode(required(arguments, "domainId")) + "/tree");
            case "search_knowledge_topics" -> core.get("/api/v1/catalog/topics/search" + searchQuery(arguments));
            case "get_knowledge_topic" -> core.get(
                "/api/v1/catalog/topics/" + encode(required(arguments, "topicId")));
            case "preview_training_domain" -> core.post("/api/v1/catalog/drafts/preview",
                without(arguments, "idempotencyKey"), key);
            case "get_training_domain_draft" -> core.get(
                "/api/v1/catalog/drafts/" + encode(required(arguments, "draftId")));
            case "confirm_training_domain" -> core.post(
                "/api/v1/catalog/drafts/" + encode(required(arguments, "draftId")) + "/confirm",
                objectMapper.createObjectNode().put("proposalHash", required(arguments, "proposalHash")), key);
            case "discard_training_domain_draft" -> core.post(
                "/api/v1/catalog/drafts/" + encode(required(arguments, "draftId")) + "/discard",
                objectMapper.createObjectNode(), key);
            case "preview_knowledge_catalog_import" -> core.post("/api/v1/catalog/imports/preview",
                without(arguments, "idempotencyKey"), key);
            case "get_knowledge_catalog_import" -> core.get(
                "/api/v1/catalog/imports/" + required(arguments, "importId"));
            case "apply_knowledge_catalog_import" -> core.post(
                "/api/v1/catalog/imports/" + required(arguments, "importId") + "/apply",
                objectMapper.createObjectNode().put("proposalHash", required(arguments, "proposalHash")), key);
            case "reject_knowledge_catalog_import" -> core.post(
                "/api/v1/catalog/imports/" + required(arguments, "importId") + "/reject",
                objectMapper.createObjectNode(), key);
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    private ObjectNode without(JsonNode source, String... names) {
        ObjectNode result = source.deepCopy();
        for (String name : names) result.remove(name);
        return result;
    }

    private String query(JsonNode arguments, String argumentName, String parameterName) {
        String value = arguments.path(argumentName).asText("").trim();
        return value.isEmpty() ? "" : "?" + parameterName + "=" + encode(value);
    }

    private String searchQuery(JsonNode arguments) {
        StringBuilder query = new StringBuilder("?q=").append(encode(required(arguments, "query")));
        appendQuery(query, "domainId", arguments.path("domainId").asText(""));
        if (arguments.has("limit")) appendQuery(query, "limit", Integer.toString(arguments.path("limit").asInt()));
        appendQuery(query, "cursor", arguments.path("cursor").asText(""));
        return query.toString();
    }

    private void appendQuery(StringBuilder query, String name, String value) {
        if (value != null && !value.isBlank()) query.append('&').append(name).append('=').append(encode(value));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String required(JsonNode arguments, String field) {
        String value = arguments.path(field).asText();
        if (value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private ObjectNode tool(String name, String description, JsonNode inputSchema) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", inputSchema);
        return tool;
    }

    private ObjectNode schema(Map<String, JsonNode> properties, List<String> required) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", objectMapper.valueToTree(properties));
        schema.set("required", objectMapper.valueToTree(required));
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode string(String description) {
        return typed("string", description);
    }

    private ObjectNode integer(String description) {
        return typed("integer", description);
    }

    private ObjectNode object(String description) {
        return typed("object", description);
    }

    private ObjectNode typed(String type, String description) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", type);
        node.put("description", description);
        return node;
    }
}
