package db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

class QuestionDomainMigrationTest {
    @Test
    void backfillsAQuestionWithOneUnambiguousDomain() throws Exception {
        try (Connection connection = database("valid")) {
            insertDomain(connection, "domain-a", "topic-a");
            insertQuestion(connection, "question-a", "[\"topic-a\"]");

            new V7__scope_questions_to_training_domains().migrate(context(connection));

            try (Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("SELECT user_id,domain_id FROM question WHERE id='question-a'")) {
                row.next();
                assertThat(row.getString("user_id")).isEqualTo("test-user");
                assertThat(row.getString("domain_id")).isEqualTo("domain-a");
            }
        }
    }

    @Test
    void rejectsAQuestionWhoseTopicsSpanDomains() throws Exception {
        try (Connection connection = database("ambiguous")) {
            insertDomain(connection, "domain-a", "topic-a");
            insertDomain(connection, "domain-b", "topic-b");
            insertQuestion(connection, "question-cross", "[\"topic-a\",\"topic-b\"]");

            assertThatThrownBy(() -> new V7__scope_questions_to_training_domains().migrate(context(connection)))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("question-cross");
        }
    }

    private Connection database(String suffix) throws Exception {
        Connection connection = DriverManager.getConnection(
            "jdbc:h2:mem:migration-" + suffix + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE app_user(id VARCHAR(120) PRIMARY KEY)");
            statement.execute("INSERT INTO app_user(id) VALUES ('test-user')");
            statement.execute("""
                CREATE TABLE knowledge_domain(
                  id VARCHAR(120) NOT NULL,user_id VARCHAR(120) NOT NULL,
                  PRIMARY KEY(user_id,id))
                """);
            statement.execute("CREATE TABLE topic(id VARCHAR(200) PRIMARY KEY,domain_id VARCHAR(120) NOT NULL)");
            statement.execute("""
                CREATE TABLE question(
                  id VARCHAR(240) PRIMARY KEY,status VARCHAR(40) NOT NULL,current_version INTEGER NOT NULL)
                """);
            statement.execute("""
                CREATE TABLE question_version(
                  question_id VARCHAR(240) NOT NULL,version INTEGER NOT NULL,topic_ids_json TEXT NOT NULL,
                  PRIMARY KEY(question_id,version))
                """);
        }
        return connection;
    }

    private void insertDomain(Connection connection, String domainId, String topicId) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO knowledge_domain(id,user_id) VALUES ('" + domainId + "','test-user')");
            statement.execute("INSERT INTO topic(id,domain_id) VALUES ('" + topicId + "','" + domainId + "')");
        }
    }

    private void insertQuestion(Connection connection, String questionId, String topicIds) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO question(id,status,current_version) VALUES ('" + questionId + "','active',1)");
            statement.execute("INSERT INTO question_version(question_id,version,topic_ids_json) VALUES ('"
                + questionId + "',1,'" + topicIds + "')");
        }
    }

    private Context context(Connection connection) {
        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
        return context;
    }
}
