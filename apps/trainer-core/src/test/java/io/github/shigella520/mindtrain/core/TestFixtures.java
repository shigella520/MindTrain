package io.github.shigella520.mindtrain.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;

final class TestFixtures {
    private TestFixtures() {}

    static void insertTopicAndActiveQuestion(JdbcClient jdbc, ObjectMapper objectMapper,
                                             String domainId, String topicId, String topicName,
                                             int importance, JsonNode question) throws Exception {
        int topicExists = jdbc.sql("SELECT COUNT(*) FROM topic WHERE id = :id")
            .param("id", topicId).query(Integer.class).single();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (topicExists == 0) {
            ObjectNode topic = objectMapper.createObjectNode()
                .put("id", topicId)
                .put("name", topicName)
                .put("kind", "leaf")
                .put("importance", importance);
            jdbc.sql("""
                    INSERT INTO topic(id, domain_id, parent_id, name, kind, importance, content_json, created_at)
                    VALUES (:id, :domainId, NULL, :name, 'leaf', :importance, :content, :createdAt)
                    """)
                .param("id", topicId).param("domainId", domainId).param("name", topicName)
                .param("importance", importance).param("content", objectMapper.writeValueAsString(topic))
                .param("createdAt", now).update();
        }

        ObjectNode active = question.deepCopy();
        active.put("status", "active");
        String questionId = active.path("id").asText();
        int version = active.path("version").asInt(1);
        jdbc.sql("""
                INSERT INTO question(id, status, current_version, session_eligible_id, created_at)
                VALUES (:id, 'active', :version, NULL, :createdAt)
                """)
            .param("id", questionId).param("version", version).param("createdAt", now).update();
        jdbc.sql("""
                INSERT INTO question_version(question_id, version, type, topic_ids_json, content_json, created_at)
                VALUES (:id, :version, :type, :topicIds, :content, :createdAt)
                """)
            .param("id", questionId).param("version", version).param("type", active.path("type").asText())
            .param("topicIds", objectMapper.writeValueAsString(active.path("topicIds")))
            .param("content", objectMapper.writeValueAsString(active)).param("createdAt", now).update();
    }
}
