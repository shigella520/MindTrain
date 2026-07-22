package io.github.shigella520.mindtrain.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "mindtrain.core.base-url=http://127.0.0.1:1",
    "mindtrain.core.token=test",
    "mindtrain.mcp.security.access-token=test-mcp-token"
})
@AutoConfigureMockMvc
class McpProtocolTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean CoreClient coreClient;

    @Test
    void initializesAndListsAllTrainerTools() throws Exception {
        mvc.perform(authenticatedPost().contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.protocolVersion").value("2025-03-26"))
            .andExpect(jsonPath("$.result.capabilities.tools").exists())
            .andExpect(jsonPath("$.result.serverInfo.version").value("0.2.0-SNAPSHOT"))
            .andExpect(jsonPath("$.result._meta.mindtrainCompatibility.contractVersion").value(1))
            .andExpect(jsonPath("$.result._meta.mindtrainCompatibility.minimumPluginVersion").value("0.2.0"));

        mvc.perform(authenticatedPost().contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.tools.length()").value(22))
            .andExpect(jsonPath("$.result.tools[0].name").value("create_training_session"));

        when(coreClient.post(anyString(), any(), any())).thenReturn(objectMapper.readTree("""
            {"id":"session-test","status":"active","targetCount":10}
            """));
        mvc.perform(authenticatedPost().contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"create_training_session","arguments":{}}}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.isError").value(false))
            .andExpect(jsonPath("$.result.structuredContent.id").value("session-test"));
    }

    @Test
    void proxiesCatalogPreviewAndApply() throws Exception {
        when(coreClient.post(anyString(), any(), any())).thenReturn(objectMapper.readTree("""
            {"importId":"catalog-test","proposalHash":"sha256:test","status":"previewed"}
            """));

        mvc.perform(authenticatedPost().contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{
                  "name":"preview_knowledge_catalog_import",
                  "arguments":{"libraryId":"java-notes","proposal":{"domains":[],"topics":[],"relations":[],"sources":[]}}
                }}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.structuredContent.importId").value("catalog-test"));
        verify(coreClient).post(eq("/api/v1/catalog/imports/preview"), any(), any());

        mvc.perform(authenticatedPost().contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{
                  "name":"apply_knowledge_catalog_import",
                  "arguments":{"importId":"catalog-test","proposalHash":"sha256:test"}
                }}
                """))
            .andExpect(status().isOk());
        verify(coreClient).post(eq("/api/v1/catalog/imports/catalog-test/apply"), any(), any());
    }

    @Test
    void proxiesKnowledgeCatalogQueriesAndDialogueDrafts() throws Exception {
        when(coreClient.get(anyString())).thenReturn(objectMapper.readTree("[]"));
        when(coreClient.post(anyString(), any(), any())).thenReturn(objectMapper.readTree("""
            {"draftId":"catalog-draft-test","importId":"catalog-draft-test","originType":"ai_dialogue","status":"previewed"}
            """));

        mvc.perform(authenticatedPost().contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{
                  "name":"search_knowledge_topics","arguments":{"query":"volatile","domainId":"test-domain"}
                }}
                """))
            .andExpect(status().isOk());
        verify(coreClient).get(eq("/api/v1/catalog/topics/search?q=volatile&domainId=test-domain"));

        mvc.perform(authenticatedPost().contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{
                  "name":"get_scheduler_backlog","arguments":{"domainId":"test-domain"}
                }}
                """))
            .andExpect(status().isOk());
        verify(coreClient).get(eq("/api/v1/schedulers/backlog?domainId=test-domain"));

        mvc.perform(authenticatedPost().contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{
                  "name":"preview_training_domain","arguments":{
                    "originType":"ai_dialogue","context":{"goal":"Learn Kubernetes"},
                    "proposal":{"domains":[],"topics":[],"relations":[],"sources":[]}
                  }
                }}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.structuredContent.originType").value("ai_dialogue"));
        verify(coreClient).post(eq("/api/v1/catalog/drafts/preview"), any(), any());
    }

    @Test
    void proxiesSavedQuestionRevision() throws Exception {
        when(coreClient.post(anyString(), any(), any())).thenReturn(objectMapper.readTree("""
            {"questionId":"java.concurrency.volatile.001","previousVersion":1,"version":2,"status":"active"}
            """));

        mvc.perform(authenticatedPost().contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                  "name":"revise_saved_question",
                  "arguments":{
                    "questionId":"java.concurrency.volatile.001",
                    "expectedVersion":1,
                    "changes":{"stem":"Revised stem"},
                    "reason":"Improve clarity"
                  }
                }}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.isError").value(false))
            .andExpect(jsonPath("$.result.structuredContent.version").value(2));

        verify(coreClient).post(eq("/api/v1/questions/java.concurrency.volatile.001/revisions"), any(), any());
    }

    @Test
    void proxiesGeneratedQuestionRejection() throws Exception {
        when(coreClient.post(anyString(), any(), any())).thenReturn(objectMapper.readTree("""
            {"assignmentId":"assignment-test","questionId":"candidate-test","rejected":true,"physicallyDeleted":true}
            """));

        mvc.perform(authenticatedPost().contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{
                  "name":"reject_generated_question","arguments":{"assignmentId":"assignment-test"}
                }}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.isError").value(false))
            .andExpect(jsonPath("$.result.structuredContent.physicallyDeleted").value(true));

        verify(coreClient).post(eq("/api/v1/assignments/assignment-test/reject"), any(), any());
    }

    @Test
    void rejectsMissingOrIncorrectBearerToken() throws Exception {
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid_or_missing_bearer_token"));

        mvc.perform(post("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                """))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsOutdatedOrUnidentifiedPluginBeforeCallingTools() throws Exception {
        mvc.perform(authenticatedPost().contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "clientInfo":{"name":"mindtrain-plugin","version":"0.1.0"}
                }}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error.code").value(-32010))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("更新或重新安装")));

        mvc.perform(post("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-mcp-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                  "name":"create_training_session","arguments":{}
                }}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error.code").value(-32010))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("未提供兼容性信息")));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticatedPost() {
        return post("/mcp")
            .header(HttpHeaders.AUTHORIZATION, "Bearer test-mcp-token")
            .header(McpCompatibility.PLUGIN_VERSION_HEADER, "0.2.0+codex.test")
            .header(McpCompatibility.CONTRACT_VERSION_HEADER, "1");
    }
}
