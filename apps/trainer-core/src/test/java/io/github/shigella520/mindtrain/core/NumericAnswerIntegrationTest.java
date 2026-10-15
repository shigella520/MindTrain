package io.github.shigella520.mindtrain.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NumericAnswerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    @ParameterizedTest
    @CsvSource({"1,A", "2,B", "3,C", "4,D", "a,A", "d,D", "我选1,A", "答案：4,D"})
    void mapsSingleOptions(String raw, String expected) throws Exception {
        String assignment = fixture(false);
        JsonNode result = answer(assignment, raw, 200);
        assertThat(result.path("score").asInt()).isEqualTo("A".equals(expected) ? 100 : 0);
        assertStored(assignment, raw, "[\"" + expected + "\"]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"13", "31", "1,3", "1，3", "1 3", "1/3", "1、3", "我选1和3", "答案：13", "113", "ac"})
    void normalizesMultipleOptionsAndPreservesRawInput(String raw) throws Exception {
        String assignment = fixture(true);
        assertThat(answer(assignment, raw, 200).path("score").asInt()).isEqualTo(100);
        assertStored(assignment, raw, "[\"A\",\"C\"]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "5", "15", "答案：15", "1是什么意思？", "1或3", "-1", "1.3", "13"})
    void invalidSingleAnswersDoNotConsumeAssignment(String raw) throws Exception {
        String assignment = fixture(false);
        assertThat(answer(assignment, raw, 400).path("code").asText()).isEqualTo("answer_unparseable");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM attempt WHERE assignment_id=:id").param("id", assignment)
            .query(Integer.class).single()).isZero();
        assertThat(answer(assignment, "1", 200).path("score").asInt()).isEqualTo(100);
    }

    private void assertStored(String assignment, String raw, String expected) throws Exception {
        var row = jdbc.sql("SELECT raw_answer, selected_option_ids_json FROM attempt WHERE assignment_id=:id")
            .param("id", assignment).query().singleRow();
        assertThat(row.get("raw_answer")).isEqualTo(raw);
        assertThat(mapper.readTree(row.get("selected_option_ids_json").toString())).isEqualTo(mapper.readTree(expected));
    }

    private String fixture(boolean multiple) throws Exception {
        String id = "numeric-" + UUID.randomUUID(), domain = id + "-domain", topic = id + "-topic";
        JsonNode question = mapper.readTree("""
            {"id":"%s","version":1,"status":"active","type":"%s","title":"Numeric answer test","stem":"Choose",
             "options":[{"id":"A","text":"alpha"},{"id":"B","text":"beta"},{"id":"C","text":"gamma"},{"id":"D","text":"delta"}],
             "correctOptionIds":%s,"topicIds":["%s"],"difficulty":2,"importance":3,
             "explanation":{"conclusion":"test","optionAnalysis":[{},{},{},{}]},
             "sources":[{"url":"https://example.com","title":"Test","accessedAt":"2026-10-15"}],
             "createdBy":"user","promptVersion":"test","createdAt":"2026-10-15T00:00:00Z"}
            """.formatted(id, multiple ? "multiple_choice" : "single_choice", multiple ? "[\"A\",\"C\"]" : "[\"A\"]", topic));
        TestFixtures.insertTopicAndActiveQuestion(jdbc, mapper, domain, topic, "Numeric", 3, question);
        String session = write("/sessions", mapper.createObjectNode().put("domainId", domain).toString(), 200).path("id").asText();
        JsonNode assignment = write("/sessions/" + session + "/assignments/next", "{}", 200).path("assignment");
        assertThat(assignment.path("answerPrompt").asText()).isEqualTo("请回复选项字母，可用逗号分隔。");
        return assignment.path("assignmentId").asText();
    }

    private JsonNode answer(String id, String raw, int expected) throws Exception {
        return write("/assignments/" + id + "/attempts", mapper.createObjectNode().put("answer", raw).toString(), expected);
    }

    private JsonNode write(String path, String body, int expected) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1" + path).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expected))
            .andReturn().getResponse().getContentAsString());
    }
}
