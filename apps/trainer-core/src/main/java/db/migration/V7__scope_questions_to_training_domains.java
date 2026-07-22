package db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V7__scope_questions_to_training_domains extends BaseJavaMigration {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        execute(connection, "ALTER TABLE question ADD COLUMN user_id VARCHAR(120)");
        execute(connection, "ALTER TABLE question ADD COLUMN domain_id VARCHAR(120)");

        List<String> invalidQuestions = new ArrayList<>();
        try (PreparedStatement questions = connection.prepareStatement("""
                SELECT q.id, qv.topic_ids_json
                FROM question q LEFT JOIN question_version qv
                  ON qv.question_id=q.id AND qv.version=q.current_version
                ORDER BY q.id
                """); ResultSet rows = questions.executeQuery()) {
            while (rows.next()) {
                String questionId = rows.getString("id");
                Set<DomainOwner> owners = domainOwners(connection, rows.getString("topic_ids_json"));
                if (owners.size() != 1) {
                    invalidQuestions.add(questionId);
                    continue;
                }
                DomainOwner owner = owners.iterator().next();
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE question SET user_id=?, domain_id=? WHERE id=?")) {
                    update.setString(1, owner.userId());
                    update.setString(2, owner.domainId());
                    update.setString(3, questionId);
                    update.executeUpdate();
                }
            }
        }
        if (!invalidQuestions.isEmpty()) {
            throw new FlywayException("Cannot assign exactly one training domain to questions: "
                + String.join(", ", invalidQuestions));
        }

        execute(connection, "ALTER TABLE question ALTER COLUMN user_id SET NOT NULL");
        execute(connection, "ALTER TABLE question ALTER COLUMN domain_id SET NOT NULL");
        execute(connection, "ALTER TABLE question ADD CONSTRAINT fk_question_user FOREIGN KEY (user_id) REFERENCES app_user(id)");
        execute(connection, "ALTER TABLE question ADD CONSTRAINT fk_question_domain FOREIGN KEY (user_id, domain_id) REFERENCES knowledge_domain(user_id, id)");
        execute(connection, "CREATE INDEX idx_question_user_domain_status ON question(user_id, domain_id, status)");
    }

    private Set<DomainOwner> domainOwners(Connection connection, String topicIdsJson) throws Exception {
        JsonNode topicIds = objectMapper.readTree(topicIdsJson);
        Set<DomainOwner> owners = new LinkedHashSet<>();
        if (topicIds == null || !topicIds.isArray() || topicIds.isEmpty()) return owners;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT d.user_id, t.domain_id
                FROM topic t JOIN knowledge_domain d ON d.id=t.domain_id
                WHERE t.id=? AND d.id=t.domain_id
                """)) {
            for (JsonNode topicId : topicIds) {
                query.setString(1, topicId.asText());
                try (ResultSet rows = query.executeQuery()) {
                    boolean found = false;
                    while (rows.next()) {
                        found = true;
                        owners.add(new DomainOwner(rows.getString("user_id"), rows.getString("domain_id")));
                    }
                    if (!found) return Set.of();
                }
            }
        }
        return owners;
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private record DomainOwner(String userId, String domainId) {}
}
