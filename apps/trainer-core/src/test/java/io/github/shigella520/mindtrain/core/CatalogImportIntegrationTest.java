package io.github.shigella520.mindtrain.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogImportIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcClient jdbc;

    @Test
    void previewsAndAppliesCatalogOnlyAfterMatchingHash() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String body = proposal(suffix);
        JsonNode preview = objectMapper.readTree(mvc.perform(post("/api/v1/catalog/imports/preview")
                .header("Idempotency-Key", "preview-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("previewed"))
            .andExpect(jsonPath("$.diff.create.length()").value(5))
            .andReturn().getResponse().getContentAsString());
        String importId = preview.path("importId").asText();
        String hash = preview.path("proposalHash").asText();

        mvc.perform(post("/api/v1/catalog/imports/{id}/apply", importId)
                .header("Idempotency-Key", "wrong-hash-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content("{\"proposalHash\":\"wrong\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("catalog_proposal_hash_mismatch"));

        String applied = mvc.perform(post("/api/v1/catalog/imports/{id}/apply", importId)
                .header("Idempotency-Key", "apply-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"proposalHash\":\"" + hash + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("applied"))
            .andReturn().getResponse().getContentAsString();
        String repeated = mvc.perform(post("/api/v1/catalog/imports/{id}/apply", importId)
                .header("Idempotency-Key", "apply-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"proposalHash\":\"" + hash + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(repeated).isEqualTo(applied);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM topic WHERE domain_id=:id")
            .param("id", "domain-" + suffix).query(Integer.class).single()).isEqualTo(2);

        JsonNode session = objectMapper.readTree(mvc.perform(post("/api/v1/sessions")
                .header("Idempotency-Key", "catalog-session-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"domainId\":\"domain-" + suffix + "\",\"schedulerProvider\":\"weighted\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v1/sessions/{id}/assignments/next", session.path("id").asText())
                .header("Idempotency-Key", "catalog-next-" + suffix))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("generation_required"))
            .andExpect(jsonPath("$.generationProfile.knowledgePoint.referenceLibraryIds[0]").value("notes"))
            .andExpect(jsonPath("$.generationProfile.knowledgePoint.sourceReferences[0].contentHash")
                .value("a".repeat(64)));

        mvc.perform(get("/api/v1/catalog/imports/{id}", importId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.proposal.sources[0].absolutePath").doesNotExist());
    }

    @Test
    void rejectsLocalSourceContentAndTopicCyclesWithoutMutatingCatalog() throws Exception {
        String suffix = UUID.randomUUID().toString();
        int importCount = jdbc.sql("SELECT COUNT(*) FROM catalog_import WHERE library_id='notes'")
            .query(Integer.class).single();
        JsonNode request = objectMapper.readTree(proposal(suffix));
        ((com.fasterxml.jackson.databind.node.ObjectNode) request.path("proposal").path("sources").get(0))
            .put("absolutePath", "/private/notes.txt");
        ((com.fasterxml.jackson.databind.node.ObjectNode) request.path("proposal").path("topics").get(0))
            .put("parentId", "topic-child-" + suffix);

        mvc.perform(post("/api/v1/catalog/imports/preview")
                .header("Idempotency-Key", "invalid-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("catalog_source_data_forbidden"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM topic WHERE domain_id=:id")
            .param("id", "domain-" + suffix).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM catalog_import WHERE library_id='notes'")
            .query(Integer.class).single()).isEqualTo(importCount);
    }

    private String proposal(String suffix) {
        return """
            {"libraryId":"notes","proposal":{
              "domains":[{"id":"domain-%1$s","name":"Distributed Systems"}],
              "sources":[{"id":"source-%1$s","sourceType":"local_reference","url":"mindtrain-local://notes/guide.md","libraryId":"notes","relativePath":"guide.md","contentHash":"%2$s","title":"Guide","accessedAt":"2026-07-21"}],
              "topics":[
                {"id":"topic-root-%1$s","domainId":"domain-%1$s","name":"Consensus","kind":"group","importance":5,"applicableVersions":[],"keywords":["consensus"],"sourceRefs":["source-%1$s"]},
                {"id":"topic-child-%1$s","domainId":"domain-%1$s","parentId":"topic-root-%1$s","name":"Raft","kind":"leaf","importance":4,"applicableVersions":[],"keywords":["raft"],"sourceRefs":["source-%1$s"]}
              ],
              "relations":[{"id":"relation-%1$s","fromTopicId":"topic-child-%1$s","toTopicId":"topic-root-%1$s","type":"requires"}]
            }}
            """.formatted(suffix, "a".repeat(64));
    }
}
