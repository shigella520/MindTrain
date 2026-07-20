package io.github.shigella520.mindtrain.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
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
            .andExpect(jsonPath("$.result.capabilities.tools").exists());

        mvc.perform(authenticatedPost().contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.tools.length()").value(8))
            .andExpect(jsonPath("$.result.tools[0].name").value("create_training_session"));

        when(coreClient.post(anyString(), any(), any())).thenReturn(objectMapper.readTree("""
            {"id":"session-test","status":"active","targetCount":10}
            """));
        mvc.perform(authenticatedPost().contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"create_training_session","arguments":{"questionCount":10}}}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result.isError").value(false))
            .andExpect(jsonPath("$.result.structuredContent.id").value("session-test"));
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

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticatedPost() {
        return post("/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer test-mcp-token");
    }
}
