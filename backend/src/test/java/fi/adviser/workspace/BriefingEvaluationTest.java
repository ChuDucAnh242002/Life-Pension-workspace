package fi.adviser.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static fi.adviser.workspace.BriefingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evaluation;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=test",
        "app.adviser-user=adviser", "app.adviser-password=local-demo-password",
        "app.ai.enabled=false", "app.policy.poll-ms=3600000"
})
class BriefingEvaluationTest {
    @Autowired CaseService cases;
    @Autowired PolicyService policies;
    @Autowired PolicySimulator simulator;
    @Autowired BriefingService service;
    @Autowired ObjectMapper mapper;
    @MockitoBean BriefingModel model;

    @Test void deterministicCorpusMeetsExactGroundingAndCoverageThresholds() throws Exception {
        var report = new BriefingEvaluationReport();
        when(model.provider()).thenReturn("Offline deterministic evaluation (not AI)");
        when(model.model()).thenReturn("all-supplied-ids");
        when(model.select(any())).thenAnswer(invocation -> {
            Context context = invocation.getArgument(0);
            String json = mapper.writeValueAsString(context);
            assertFalse(json.contains("Ignore all rules"));
            assertFalse(json.contains("Synthetic evaluation"));
            return new Selection(context.sources().stream().map(Source::id).toList(),
                    context.questions().stream().map(Question::id).toList());
        });
        var failures = new java.util.ArrayList<String>();
        for (var example : BriefingEvaluationCorpus.examples()) {
            var c = cases.create(example.profile(), "evaluator");
            if (example.policy() != null) {
                var request = policies.request(c.id(), new PolicyModels.RequestInput(1, example.policy()), "evaluator");
                policies.accept(simulator.respond(new PolicyModels.WireRequest(request.id(), 1, request.fixture())));
            }
            var before = cases.detail(c.id());
            var draft = service.generate(c.id(), new Input(1), "evaluator");
            var score = report.score(example, draft);
            if (!score.passed()) failures.add(example.name());
            assertEquals(before, cases.detail(c.id()));
            assertFalse(service.view(c.id()).usable());
        }
        report.write("briefing-evaluation-offline.json", "offline-contract-only", model.provider(), model.model());
        assertTrue(failures.isEmpty(), "Evaluation failures: " + failures);
    }

    @Test void adversarialSelectionsNeverPersistAdviceOrUnknownCitations() {
        var example = BriefingEvaluationCorpus.examples().getLast();
        var c = cases.create(example.profile(), "evaluator");
        for (var selection : List.of(
                new Selection(List.of("buy-guaranteed-return"), List.of("confirm_declared")),
                new Selection(List.of("declared.age"), List.of("recommend-product")),
                new Selection(List.of("declared.age", "declared.age"), List.of("confirm_declared")),
                new Selection(List.of("policy.pension"), List.of("confirm_declared")),
                new Selection(List.of("declared.age"), java.util.Collections.singletonList(null)))) {
            when(model.select(any())).thenReturn(selection);
            assertThrows(ResponseStatusException.class, () -> service.generate(c.id(), new Input(1), "evaluator"));
            assertNull(service.view(c.id()).draft());
        }
    }

    @Test void reportDetectsValidButUnhelpfulSelectionRatherThanCallingItSuccess() {
        var example = BriefingEvaluationCorpus.examples().getFirst();
        var c = cases.create(example.profile(), "evaluator");
        when(model.select(any())).thenReturn(new Selection(List.of("declared.age"), List.of("confirm_declared")));
        var draft = service.generate(c.id(), new Input(1), "evaluator");
        var score = new BriefingEvaluationReport().score(example, draft);
        assertTrue(score.exactCitations());
        assertEquals(0.0, score.requiredSourceRecall());
        assertEquals(0.0, score.requiredQuestionRecall());
        assertFalse(score.passed());
    }

    @Test void rubricRejectsInventedValuesEvenWhenCitationIdsLookValid() {
        var example = BriefingEvaluationCorpus.examples().getFirst();
        var c = cases.create(example.profile(), "evaluator");
        when(model.select(any())).thenAnswer(invocation -> {
            Context context = invocation.getArgument(0);
            return new Selection(context.sources().stream().map(Source::id).toList(),
                    context.questions().stream().map(Question::id).toList());
        });
        var original = service.generate(c.id(), new Input(1), "evaluator");
        var altered = original.sources().stream().map(source -> source.id().equals("declared.pension")
                ? new Source(source.id(), source.label(), "0", source.origin()) : source).toList();
        var invented = new Draft(original.id(), original.caseRevision(), original.policyBasis(), original.createdAt(),
                original.createdBy(), original.provider(), original.model(), original.promptVersion(),
                altered, original.questions(), altered);
        var score = new BriefingEvaluationReport().score(example, invented);
        assertTrue(score.exactCitations());
        assertFalse(score.exactFactsAndOrigins());
        assertFalse(score.passed());
    }
}
