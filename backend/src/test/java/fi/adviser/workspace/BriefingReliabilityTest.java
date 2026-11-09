package fi.adviser.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static fi.adviser.workspace.BriefingModels.*;
import static fi.adviser.workspace.CaseModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reliability;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=test",
        "app.adviser-user=adviser", "app.adviser-password=local-demo-password",
        "app.ai.enabled=false", "app.policy.poll-ms=3600000"
})
class BriefingReliabilityTest {
    @Autowired CaseService cases;
    @Autowired BriefingService service;
    @MockitoBean BriefingModel model;
    @BeforeEach void configure() {
        when(model.provider()).thenReturn("Offline reliability test");
        when(model.model()).thenReturn("fixed");
        when(model.select(any())).thenReturn(new Selection(List.of("declared.age"), List.of("confirm_declared")));
    }

    @Test void editProceedsWhileInferenceBlockedAndObsoleteResponseCannotOverwriteApproval() throws Exception {
        var c = cases.create(BriefingEvaluationCorpus.examples().getFirst().profile(), "evaluator");
        var draft = service.generate(c.id(), new Input(1), "evaluator");
        service.approve(c.id(), new ApprovalInput(UUID.fromString(draft.id()), 1, true, null), "reviewer");
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(model.select(any())).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(finish.await(10, TimeUnit.SECONDS));
            return new Selection(List.of("declared.age"), List.of("confirm_declared"));
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var inference = executor.submit(() -> service.generate(c.id(), new Input(1), "evaluator"));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                executor.submit(() -> cases.update(c.id(), new ProfileUpdate(1, c.profile()), "evaluator"))
                        .get(5, TimeUnit.SECONDS);
            } finally { finish.countDown(); }
            var failure = assertThrows(ExecutionException.class, () -> inference.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ResponseStatusException.class, failure.getCause());
            assertEquals(409, ((ResponseStatusException) failure.getCause()).getStatusCode().value());
        }
        assertEquals(draft, service.view(c.id()).draft());
        assertEquals(ApprovalStatus.STALE, service.view(c.id()).approvals().getFirst().status());
        assertThrows(ResponseStatusException.class, () -> service.approved(c.id()));
    }

    @Test void parallelApprovalAndNewGenerationNeverAuthorizeSupersededDraft() throws Exception {
        var c = cases.create(BriefingEvaluationCorpus.examples().getFirst().profile(), "evaluator");
        var first = service.generate(c.id(), new Input(1), "evaluator");
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var approval = executor.submit(() -> {
                gate.await();
                try { service.approve(c.id(), new ApprovalInput(UUID.fromString(first.id()), 1, true, null), "reviewer"); }
                catch (ResponseStatusException failure) { assertEquals(409, failure.getStatusCode().value()); }
                return true;
            });
            var generation = executor.submit(() -> { gate.await(); return service.generate(c.id(), new Input(1), "evaluator"); });
            gate.countDown();
            var second = generation.get(10, TimeUnit.SECONDS);
            assertTrue(approval.get(10, TimeUnit.SECONDS));
            assertEquals(second, service.view(c.id()).draft());
        }
        assertFalse(service.view(c.id()).usable());
        assertTrue(service.view(c.id()).approvals().stream().allMatch(a -> a.status() == ApprovalStatus.SUPERSEDED));
        assertThrows(ResponseStatusException.class, () -> service.approved(c.id()));
    }
}
