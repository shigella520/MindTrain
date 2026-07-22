package io.github.shigella520.mindtrain.core.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.shigella520.mindtrain.core.catalog.CatalogService;
import io.github.shigella520.mindtrain.core.config.ApplicationSettingsService;
import io.github.shigella520.mindtrain.core.config.ApplicationSettingsService.TrainingSettings;
import io.github.shigella520.mindtrain.core.config.ApplicationSettingsService.UpdateTrainingSettingsRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

@RestController
@RequestMapping("/api/v1")
public class CoreApiController {
    private final TrainingService training;
    private final QuestionService questions;
    private final IdempotencyService idempotency;
    private final ApplicationSettingsService applicationSettings;
    private final CatalogService catalog;

    public CoreApiController(TrainingService training, QuestionService questions,
                             IdempotencyService idempotency,
                             ApplicationSettingsService applicationSettings,
                             CatalogService catalog) {
        this.training = training;
        this.questions = questions;
        this.idempotency = idempotency;
        this.applicationSettings = applicationSettings;
        this.catalog = catalog;
    }

    @PostMapping("/sessions")
    public TrainingService.SessionResponse createSession(
        @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) CreateSessionRequest request) {
        CreateSessionRequest body = request == null ? new CreateSessionRequest(null, null) : request;
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

    @PostMapping("/assignments/{id}/reject")
    public TrainingService.RejectedCandidateResponse reject(@PathVariable String id,
                                                              @RequestHeader("Idempotency-Key") String key) {
        return idempotency.execute("reject-candidate:" + id, key,
            TrainingService.RejectedCandidateResponse.class, () -> training.rejectCandidate(id));
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
            () -> questions.reviseActive(id, request.expectedVersion(), request.changes(), request.reason(),
                request.sourceAssignmentId(), request.model(), request.promptVersion()));
    }

    @GetMapping("/reports/overview")
    public JsonNode overview() {
        return training.overview();
    }

    @GetMapping("/schedulers/backlog")
    public SchedulerProvider.Backlog backlog(
        @RequestParam(name = "domainId", required = false) String domainId) {
        return training.backlog(domainId);
    }

    @GetMapping("/settings/training")
    public TrainingSettings trainingSettings() {
        return applicationSettings.get();
    }

    @PutMapping("/settings/training")
    public TrainingSettings updateTrainingSettings(@RequestHeader("Idempotency-Key") String key,
                                                    @RequestBody UpdateTrainingSettingsRequest request) {
        return idempotency.execute("update-training-settings", key, TrainingSettings.class,
            () -> applicationSettings.update(request));
    }

    @PostMapping({"/catalog/drafts/preview", "/catalog/imports/preview"})
    public CatalogService.ImportResponse previewCatalog(@RequestHeader("Idempotency-Key") String key,
                                                         @RequestBody CatalogService.PreviewRequest request) {
        return idempotency.execute("preview-catalog-import", key, CatalogService.ImportResponse.class,
            () -> catalog.preview(request));
    }

    @GetMapping({"/catalog/drafts/{id}", "/catalog/imports/{id}"})
    public CatalogService.ImportResponse getCatalogImport(@PathVariable String id) {
        return catalog.get(id);
    }

    @PostMapping({"/catalog/drafts/{id}/confirm", "/catalog/imports/{id}/apply"})
    public CatalogService.ImportResponse applyCatalog(@PathVariable String id,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       @RequestBody CatalogService.ApplyRequest request) {
        return idempotency.execute("apply-catalog-import:" + id, key, CatalogService.ImportResponse.class,
            () -> catalog.apply(id, request.proposalHash()));
    }

    @PostMapping({"/catalog/drafts/{id}/discard", "/catalog/imports/{id}/reject"})
    public CatalogService.ImportResponse rejectCatalog(@PathVariable String id,
                                                        @RequestHeader("Idempotency-Key") String key) {
        return idempotency.execute("reject-catalog-import:" + id, key, CatalogService.ImportResponse.class,
            () -> catalog.reject(id));
    }

    @GetMapping("/catalog/domains")
    public java.util.List<CatalogService.DomainSummary> knowledgeDomains(
        @RequestParam(name = "q", required = false) String query) {
        return catalog.domains(query);
    }

    @GetMapping("/catalog/domains/{domainId}/tree")
    public CatalogService.TopicTreeResponse knowledgeDomainTree(@PathVariable String domainId) {
        return catalog.tree(domainId);
    }

    @GetMapping("/catalog/topics/search")
    public CatalogService.TopicSearchResponse searchKnowledgeTopics(
        @RequestParam("q") String query,
        @RequestParam(name = "domainId", required = false) String domainId,
        @RequestParam(name = "limit", defaultValue = "20") int limit,
        @RequestParam(name = "cursor", required = false) String cursor) {
        return catalog.search(query, domainId, limit, cursor);
    }

    @GetMapping("/catalog/topics/{topicId}")
    public CatalogService.TopicDetail knowledgeTopic(@PathVariable String topicId) {
        return catalog.topic(topicId);
    }

    public record CandidateRequest(String sessionId, String topicId, JsonNode question,
                                   String attemptType, String parentAttemptId) {}
    public record RevisionRequest(int expectedVersion, JsonNode changes, String reason,
                                  String sourceAssignmentId, String model, String promptVersion) {}
}
