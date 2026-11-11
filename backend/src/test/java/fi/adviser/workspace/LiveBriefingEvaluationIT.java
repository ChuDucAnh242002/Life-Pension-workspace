package fi.adviser.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static fi.adviser.workspace.BriefingModels.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:liveevaluation;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=test",
        "app.adviser-user=adviser", "app.adviser-password=local-demo-password",
        "app.policy.poll-ms=3600000"
})
class LiveBriefingEvaluationIT {
    @BeforeAll static void requirePaidSyntheticConsent() {
        assertEquals("accept-paid-synthetic-only", System.getenv("LIVE_AI_EVALUATION"),
                "Live evaluation requires explicit LIVE_AI_EVALUATION=accept-paid-synthetic-only authorization.");
    }
    @Autowired CaseService cases;
    @Autowired PolicyService policies;
    @Autowired PolicySimulator simulator;
    @Autowired BriefingService service;
    @Autowired BriefingModel model;

    @Test void evaluateSixSyntheticExamplesWithOneCallEachAndNoRetry() throws Exception {
        assertTrue(model.enabled(), "Set AI_ENABLED=true and explicitly configure a backend.");
        var report = new BriefingEvaluationReport();
        var failures = new java.util.ArrayList<String>();
        try {
            for (var example : BriefingEvaluationCorpus.examples()) {
                var c = cases.create(example.profile(), "synthetic-evaluator");
                if (example.policy() != null) {
                    var request = policies.request(c.id(), new PolicyModels.RequestInput(1, example.policy()), "synthetic-evaluator");
                    policies.accept(simulator.respond(new PolicyModels.WireRequest(request.id(), 1, request.fixture())));
                }
                var draft = service.generate(c.id(), new Input(1), "synthetic-evaluator");
                if (!report.score(example, draft).passed()) failures.add(example.name());
            }
        } finally {
            report.write("briefing-evaluation-live.json", "paid-synthetic-model-evaluation", model.provider(), model.model());
        }
        assertTrue(failures.isEmpty(), "Model missed required corpus facts/questions: " + failures);
    }
}
