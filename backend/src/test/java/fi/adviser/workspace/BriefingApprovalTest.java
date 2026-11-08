package fi.adviser.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static fi.adviser.workspace.BriefingModels.*;
import static fi.adviser.workspace.CaseModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:approvals;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=test",
        "app.adviser-user=adviser", "app.adviser-password=local-demo-password",
        "app.policy.poll-ms=3600000"
})
@AutoConfigureMockMvc
class BriefingApprovalTest {
    @Autowired BriefingService service;
    @Autowired CaseService cases;
    @Autowired PolicyService policies;
    @Autowired PolicySimulator simulator;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean BriefingModel model;

    @BeforeEach void configure() {
        when(model.enabled()).thenReturn(true);
        when(model.provider()).thenReturn("Local approval test");
        when(model.model()).thenReturn("test");
        when(model.select(any())).thenReturn(new Selection(List.of("declared.age"), List.of("confirm_declared")));
    }
    private CaseView create() {
        return cases.create(new Profile("Synthetic approval", 38, Employment.UNKNOWN, null, null, null, null, null), "adviser");
    }
    private ApprovalInput input(Draft draft, boolean reviewed, String note) {
        return new ApprovalInput(UUID.fromString(draft.id()), draft.caseRevision(), reviewed, note);
    }

    @Test void requiresExplicitReviewAndPersistsActorTimeNoteWithoutChangingFactsOrHistory() {
        var c = create();
        var before = cases.detail(c.id());
        var draft = service.generate(c.id(), new Input(1), "adviser");
        assertFalse(service.view(c.id()).usable());
        assertThrows(ResponseStatusException.class, () -> service.approved(c.id()));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.approve(c.id(), input(draft, false, null), "adviser")).getStatusCode().value());
        var approval = service.approve(c.id(), input(draft, true, "  Checked synthetic sources.  "), "reviewer");
        assertEquals("reviewer", approval.approvedBy());
        assertEquals("Checked synthetic sources.", approval.note());
        assertNotNull(approval.approvedAt());
        assertEquals(ApprovalStatus.CURRENT, approval.status());
        assertTrue(service.view(c.id()).usable());
        assertEquals(draft, service.approved(c.id()));
        assertEquals(before, cases.detail(c.id()));
        assertEquals(approval, service.approve(c.id(), input(draft, true, "different"), "another"));
        assertEquals(1, service.view(c.id()).approvals().size());
    }

    @Test void factChangesRetainStaleEvidenceButBlockApprovedEndpointAndReapproval() {
        var c = create();
        var draft = service.generate(c.id(), new Input(1), "adviser");
        var approval = service.approve(c.id(), input(draft, true, null), "adviser");
        cases.update(c.id(), new ProfileUpdate(1, c.profile()), "adviser");
        var view = service.view(c.id());
        assertTrue(view.stale());
        assertFalse(view.usable());
        assertEquals(ApprovalStatus.STALE, view.approvals().getFirst().status());
        assertEquals(approval.approvedAt(), view.approvals().getFirst().approvedAt());
        assertThrows(ResponseStatusException.class, () -> service.approved(c.id()));
        assertThrows(ResponseStatusException.class, () -> service.approve(c.id(), input(draft, true, null), "adviser"));
    }

    @Test void newerDraftSupersedesApprovalEvenWhenSourcesAreIdentical() {
        var c = create();
        var first = service.generate(c.id(), new Input(1), "adviser");
        service.approve(c.id(), input(first, true, null), "adviser");
        var second = service.generate(c.id(), new Input(1), "adviser");
        assertEquals(ApprovalStatus.SUPERSEDED, service.view(c.id()).approvals().getFirst().status());
        assertFalse(service.view(c.id()).usable());
        assertThrows(ResponseStatusException.class, () -> service.approved(c.id()));
        assertThrows(ResponseStatusException.class, () -> service.approve(c.id(), input(first, true, null), "adviser"));
        service.approve(c.id(), input(second, true, null), "adviser");
        assertEquals(second, service.approved(c.id()));
        assertEquals(2, service.view(c.id()).approvals().size());
    }

    @Test void policyRequestAndReplyChangesInvalidateApprovalWithoutCaseRevisionChange() {
        var c = create();
        var request = policies.request(c.id(), new PolicyModels.RequestInput(1, PolicyModels.Fixture.SOLE_TRADER), "adviser");
        var draft = service.generate(c.id(), new Input(1), "adviser");
        service.approve(c.id(), input(draft, true, null), "adviser");
        policies.accept(simulator.respond(new PolicyModels.WireRequest(request.id(), 1, request.fixture())));
        assertFalse(service.view(c.id()).usable());
        assertThrows(ResponseStatusException.class, () -> service.approved(c.id()));
        var next = service.generate(c.id(), new Input(1), "adviser");
        service.approve(c.id(), input(next, true, null), "adviser");
        policies.request(c.id(), new PolicyModels.RequestInput(1, PolicyModels.Fixture.EMPLOYEE), "adviser");
        assertFalse(service.view(c.id()).usable());
        assertThrows(ResponseStatusException.class, () -> service.approved(c.id()));
    }

    @Test void rejectsCrossCaseWrongRevisionMissingDraftAndClosedCasesWithoutWrites() {
        var c = create();
        var other = create();
        var draft = service.generate(c.id(), new Input(1), "adviser");
        assertThrows(ResponseStatusException.class, () -> service.approve(other.id(), input(draft, true, null), "adviser"));
        assertThrows(ResponseStatusException.class, () -> service.approve(c.id(),
                new ApprovalInput(UUID.fromString(draft.id()), 2, true, null), "adviser"));
        assertThrows(ResponseStatusException.class, () -> service.approve(c.id(),
                new ApprovalInput(UUID.randomUUID(), 1, true, null), "adviser"));
        cases.transition(c.id(), new StatusUpdate(1, Status.IN_REVIEW), "adviser");
        cases.transition(c.id(), new StatusUpdate(2, Status.CLOSED), "adviser");
        assertThrows(ResponseStatusException.class, () -> service.approve(c.id(), input(draft, true, null), "adviser"));
        assertTrue(service.view(c.id()).approvals().isEmpty());
    }

    @Test void concurrentDuplicateApprovalStoresOnlyOneImmutableRecord() throws Exception {
        var c = create();
        var draft = service.generate(c.id(), new Input(1), "adviser");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Approval> command = () -> { gate.await(); return service.approve(c.id(), input(draft, true, null), "adviser"); };
            var first = executor.submit(command);
            var second = executor.submit(command);
            gate.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM briefing_approval WHERE draft_id=?", Integer.class, draft.id()));
    }

    @Test void approvalWorksWithInferenceDisabledAndDoesNotInvokeModel() {
        var c = create();
        var draft = service.generate(c.id(), new Input(1), "adviser");
        clearInvocations(model);
        when(model.enabled()).thenReturn(false);
        service.approve(c.id(), input(draft, true, "Offline review"), "adviser");
        assertEquals(draft, service.approved(c.id()));
        verify(model, never()).select(any());
    }

    @Test void endpointsEnforceAuthCsrfConfirmationAndNoteLimit() throws Exception {
        var c = create();
        var draft = service.generate(c.id(), new Input(1), "adviser");
        String path = "/api/cases/" + c.id() + "/briefing";
        String body = "{\"draftId\":\"" + draft.id() + "\",\"revision\":1,\"reviewedSources\":true}";
        mvc.perform(get(path + "/approved")).andExpect(status().isUnauthorized());
        mvc.perform(post(path + "/approval").with(user("adviser")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(path + "/approval").with(user("adviser")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("true", "false"))).andExpect(status().isBadRequest());
        mvc.perform(post(path + "/approval").with(user("adviser")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body.substring(0, body.length() - 1) + ",\"note\":\"" + "a".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path + "/approval").with(user("reviewer")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.approvedBy").value("reviewer"));
        mvc.perform(get(path + "/approved").with(user("adviser"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(draft.id()));
    }
}
