package io.github.shigella520.mindtrain.core.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.shigella520.mindtrain.core.importer.PrototypeImportService;
import io.github.shigella520.mindtrain.core.question.QuestionService;
import io.github.shigella520.mindtrain.core.scheduling.SchedulerProvider;
import io.github.shigella520.mindtrain.core.training.TrainingService;
import io.github.shigella520.mindtrain.core.training.TrainingService.CreateSessionRequest;
import io.github.shigella520.mindtrain.core.training.TrainingService.InteractionRequest;
import io.github.shigella520.mindtrain.core.training.TrainingService.SubmitAttemptRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CoreApiController {
    private final TrainingService training;
    private final QuestionService questions;
    private final PrototypeImportService imports;
    private final IdempotencyService idempotency;

    public CoreApiController(TrainingService training, QuestionService questions,
                             PrototypeImportService imports, IdempotencyService idempotency) {
        this.training = training;
        this.questions = questions;
        this.imports = imports;
        this.idempotency = idempotency;
    }

    @PostMapping("/sessions")
    public TrainingService.SessionResponse createSession(
        @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) CreateSessionRequest request) {
        CreateSessionRequest body = request == null ? new CreateSessionRequest(null, null, null) : request;
        return idempotency.execute("create-session", key, TrainingService.SessionResponse.class,
            () -> training.createSession(body));
    }

    @PostMapping("/sessions/{id}/assignments/next")
    public TrainingService.NextAssignmentResponse next(@PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
        return idempotency.execute("next-assignment:" + id, key, TrainingService.NextAssignmentResponse.class,
            () -> training.nextAssignment(id));
    }

    @PostMapping("/assignments/{id}/attempts")
    public TrainingService.AttemptResponse answer(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                                   @RequestBody SubmitAttemptRequest request) {
        return idempotency.execute("submit-attempt:" + id, key, TrainingService.AttemptResponse.class,
            () -> training.submitAttempt(id, request));
    }

    @PostMapping("/sessions/{id}/interactions")
    public TrainingService.InteractionResponse interaction(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                                            @RequestBody InteractionRequest request) {
        return idempotency.execute("record-interaction:" + id, key, TrainingService.InteractionResponse.class,
            () -> training.recordInteraction(id, request));
    }

    @PostMapping("/sessions/{id}/finish")
    public TrainingService.SessionResponse finish(@PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
        return idempotency.execute("finish-session:" + id, key, TrainingService.SessionResponse.class,
            () -> training.finishSession(id));
    }

    @PostMapping("/candidates")
    public QuestionService.CandidateResponse candidate(@RequestHeader("Idempotency-Key") String key,
                                                        @RequestBody CandidateRequest request) {
        return idempotency.execute("create-candidate:" + request.sessionId(), key, QuestionService.CandidateResponse.class,
            () -> questions.createCandidate(request.sessionId(), request.topicId(), request.question(),
                request.attemptType(), request.parentAttemptId()));
    }

    @PostMapping("/questions/{id}/revisions")
    public QuestionService.RevisionResponse reviseQuestion(@PathVariable String id,
                                                            @RequestHeader("Idempotency-Key") String key,
                                                            @RequestBody RevisionRequest request) {
        return idempotency.execute("revise-question", key, QuestionService.RevisionResponse.class,
            () -> questions.revisePublished(id, request.expectedVersion(), request.changes(), request.reason(),
                request.sourceAssignmentId(), request.model(), request.promptVersion()));
    }

    @GetMapping("/reports/overview")
    public JsonNode overview() {
        return training.overview();
    }

    @GetMapping("/schedulers/backlog")
    public SchedulerProvider.Backlog backlog() {
        return training.backlog();
    }

    @PostMapping("/imports/prototype")
    public PrototypeImportService.ImportResponse importPrototype(@RequestHeader("Idempotency-Key") String key,
                                                                  @RequestBody JsonNode request) {
        return idempotency.execute("prototype-import", key, PrototypeImportService.ImportResponse.class,
            () -> imports.importPrototype(request));
    }

    @GetMapping("/imports/{id}")
    public PrototypeImportService.ImportResponse importReport(@PathVariable String id) {
        return imports.get(id);
    }

    public record CandidateRequest(String sessionId, String topicId, JsonNode question,
                                   String attemptType, String parentAttemptId) {}
    public record RevisionRequest(int expectedVersion, JsonNode changes, String reason,
                                  String sourceAssignmentId, String model, String promptVersion) {}
}
