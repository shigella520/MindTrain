package io.github.shigella520.mindtrain.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

    @Test
    void createsAiDialogueDomainAndQueriesMultipleRootsSearchAndDetails() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String domainId = "kubernetes-" + suffix;
        String previewBody = """
            {"originType":"ai_dialogue","context":{"goal":"Learn Kubernetes for backend work"},"proposal":{
              "domains":[{"id":"%1$s","name":"Kubernetes","description":"Backend-oriented Kubernetes","sortOrder":2}],
              "sources":[],
              "topics":[
                {"id":"workloads-%2$s","domainId":"%1$s","name":"Workloads","description":"Deploying applications","kind":"group","importance":5,"sortOrder":1,"keywords":["deployment","pod"],"sourceRefs":[]},
                {"id":"networking-%2$s","domainId":"%1$s","name":"Networking","description":"Service discovery and traffic","kind":"group","importance":4,"sortOrder":2,"keywords":["service","dns"],"sourceRefs":[]},
                {"id":"deployment-%2$s","domainId":"%1$s","parentId":"workloads-%2$s","name":"Deployment","description":"Declarative rollout controller","kind":"leaf","importance":5,"sortOrder":1,"keywords":["rollout"],"sourceRefs":[]}
              ],
              "relations":[]
            }}
            """.formatted(domainId, suffix);
        JsonNode preview = objectMapper.readTree(mvc.perform(post("/api/v1/catalog/drafts/preview")
                .header("Idempotency-Key", "ai-preview-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(previewBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.originType").value("ai_dialogue"))
            .andExpect(jsonPath("$.draftId").isNotEmpty())
            .andExpect(jsonPath("$.libraryId").doesNotExist())
            .andExpect(jsonPath("$.validation.warnings[0]").value("AI dialogue draft has no bound reference sources"))
            .andReturn().getResponse().getContentAsString());

        mvc.perform(post("/api/v1/catalog/drafts/{id}/confirm", preview.path("importId").asText())
                .header("Idempotency-Key", "ai-confirm-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"proposalHash\":\"" + preview.path("proposalHash").asText() + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("applied"));

        mvc.perform(get("/api/v1/catalog/domains").param("q", "backend-oriented"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(domainId))
            .andExpect(jsonPath("$[0].rootTopicCount").value(2))
            .andExpect(jsonPath("$[0].topicCount").value(3));
        mvc.perform(get("/api/v1/catalog/domains/{id}/tree", domainId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roots.length()").value(2))
            .andExpect(jsonPath("$.roots[0].name").value("Workloads"))
            .andExpect(jsonPath("$.roots[0].children[0].name").value("Deployment"));
        mvc.perform(get("/api/v1/catalog/topics/search").param("q", "rollout").param("domainId", domainId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].ancestorPath[0]").value("Workloads"));
        mvc.perform(get("/api/v1/catalog/topics/{id}", "deployment-" + suffix))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.domainName").value("Kubernetes"))
            .andExpect(jsonPath("$.keywords[0]").value("rollout"));
    }

    @Test
    void aggregatesDistinctActiveQuestionsAcrossTopicSubtrees() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String domainId = "recursive-domain-" + suffix;
        String rootId = "recursive-root-" + suffix;
        String branchId = "recursive-branch-" + suffix;
        String leafAId = "recursive-leaf-a-" + suffix;
        String leafBId = "recursive-leaf-b-" + suffix;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO knowledge_domain(id,user_id,name,content_json,created_at,origin_type,sort_order,updated_at)
                VALUES (:id,'test-user','Recursive Domain','{}',:now,'ai_dialogue',0,:now)
                """).param("id", domainId).param("now", now).update();
        insertTopic(rootId, domainId, null, "Root", "group", 0, now);
        insertTopic(branchId, domainId, rootId, "Branch", "group", 0, now);
        insertTopic(leafAId, domainId, branchId, "Leaf A", "leaf", 0, now);
        insertTopic(leafBId, domainId, branchId, "Leaf B", "leaf", 1, now);
        insertQuestion("recursive-question-a-" + suffix, domainId, List.of(leafAId), now);
        insertQuestion("recursive-question-shared-" + suffix, domainId, List.of(leafAId, leafBId), now);
        insertQuestion("recursive-question-root-" + suffix, domainId, List.of(rootId), now);

        mvc.perform(get("/api/v1/catalog/domains/{id}/tree", domainId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.domain.activeQuestionCount").value(3))
            .andExpect(jsonPath("$.roots[0].activeQuestionCount").value(3))
            .andExpect(jsonPath("$.roots[0].children[0].activeQuestionCount").value(2))
            .andExpect(jsonPath("$.roots[0].children[0].children[0].activeQuestionCount").value(2))
            .andExpect(jsonPath("$.roots[0].children[0].children[1].activeQuestionCount").value(1));
        mvc.perform(get("/api/v1/catalog/topics/search").param("q", "Branch").param("domainId", domainId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].activeQuestionCount").value(2));
        mvc.perform(get("/api/v1/catalog/topics/{id}", branchId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeQuestionCount").value(2))
            .andExpect(jsonPath("$.children[0].activeQuestionCount").value(2))
            .andExpect(jsonPath("$.children[1].activeQuestionCount").value(1));
    }

    private void insertTopic(String id, String domainId, String parentId, String name, String kind,
                             int sortOrder, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO topic(id,domain_id,parent_id,name,kind,importance,content_json,sort_order,created_at,updated_at)
                VALUES (:id,:domainId,:parentId,:name,:kind,3,'{}',:sortOrder,:now,:now)
                """).param("id", id).param("domainId", domainId).param("parentId", parentId)
            .param("name", name).param("kind", kind).param("sortOrder", sortOrder).param("now", now).update();
    }

    private void insertQuestion(String id, String domainId, List<String> topicIds, OffsetDateTime now)
            throws Exception {
        jdbc.sql("""
                INSERT INTO question(id,user_id,domain_id,status,current_version,created_at)
                VALUES (:id,'test-user',:domainId,'active',1,:now)
                """).param("id", id).param("domainId", domainId).param("now", now).update();
        jdbc.sql("""
                INSERT INTO question_version(question_id,version,type,topic_ids_json,content_json,created_at)
                VALUES (:id,1,'single_choice',:topicIds,'{}',:now)
                """).param("id", id).param("topicIds", objectMapper.writeValueAsString(topicIds))
            .param("now", now).update();
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
