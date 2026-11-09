package fi.adviser.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import java.nio.file.Path;
import java.util.UUID;
import static fi.adviser.workspace.BriefingModels.*;
import static fi.adviser.workspace.CaseModels.*;
import static org.junit.jupiter.api.Assertions.*;

class BriefingPersistenceTest {
    @TempDir Path directory;

    @TestConfiguration(proxyBeanMethods = false)
    static class OfflineModel {
        @Bean @Primary BriefingModel deterministicModel() {
            return new BriefingModel() {
                public boolean enabled() { return false; }
                public String provider() { return "Offline persistence test"; }
                public String model() { return "all-supplied-ids"; }
                public Selection select(Context context) {
                    return new Selection(context.sources().stream().map(Source::id).toList(),
                            context.questions().stream().map(Question::id).toList());
                }
            };
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(WorkspaceApplication.class, OfflineModel.class).run(
                "--server.port=0", "--spring.main.banner-mode=off",
                "--spring.datasource.url=jdbc:h2:file:" + directory.resolve("workspace")
                        + ";MODE=Oracle;DB_CLOSE_ON_EXIT=FALSE",
                "--spring.datasource.username=sa", "--spring.datasource.password=test",
                "--app.adviser-user=adviser", "--app.adviser-password=local-demo-password",
                "--app.ai.enabled=false", "--app.ai.backend=huggingface",
                "--app.policy.transport=demo", "--app.policy.poll-ms=3600000");
    }

    @Test void exactFactsPolicyDraftAndApprovalSurviveFullContextRestartThenStaleEvidenceSurvivesAnother() {
        long caseId;
        CaseDetail detail;
        Draft draft;
        Approval approval;
        PolicyModels.Workspace policy;
        try (var app = start()) {
            var cases = app.getBean(CaseService.class);
            var policies = app.getBean(PolicyService.class);
            var briefings = app.getBean(BriefingService.class);
            var example = BriefingEvaluationCorpus.examples().get(3);
            var c = cases.create(example.profile(), "evaluator");
            caseId = c.id();
            var request = policies.request(caseId, new PolicyModels.RequestInput(1, example.policy()), "evaluator");
            policies.accept(app.getBean(PolicySimulator.class).respond(new PolicyModels.WireRequest(request.id(), 1, request.fixture())));
            draft = briefings.generate(caseId, new Input(1), "evaluator");
            approval = briefings.approve(caseId, new ApprovalInput(UUID.fromString(draft.id()), 1, true, "Restart verification"), "reviewer");
            detail = cases.detail(caseId);
            policy = policies.workspace(caseId, "IN_PROCESS_DEMO");
        }
        try (var app = start()) {
            var briefings = app.getBean(BriefingService.class);
            var cases = app.getBean(CaseService.class);
            assertEquals(detail, cases.detail(caseId));
            assertEquals(policy, app.getBean(PolicyService.class).workspace(caseId, "IN_PROCESS_DEMO"));
            assertEquals(draft, briefings.approved(caseId));
            assertEquals(approval, briefings.view(caseId).approvals().getFirst());
            assertTrue(briefings.view(caseId).usable());
            cases.update(caseId, new ProfileUpdate(1, detail.caseData().profile()), "evaluator");
            assertFalse(briefings.view(caseId).usable());
            detail = cases.detail(caseId);
        }
        try (var app = start()) {
            var briefings = app.getBean(BriefingService.class);
            assertEquals(detail, app.getBean(CaseService.class).detail(caseId));
            var view = briefings.view(caseId);
            assertEquals(draft, view.draft());
            assertTrue(view.stale());
            assertFalse(view.usable());
            assertEquals(ApprovalStatus.STALE, view.approvals().getFirst().status());
            assertEquals(approval.approvedAt(), view.approvals().getFirst().approvedAt());
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> briefings.approved(caseId));
        }
    }
}
