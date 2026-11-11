package fi.adviser.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.*;
import static fi.adviser.workspace.CaseModels.*;
import static fi.adviser.workspace.PolicyModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:policy;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=test",
        "app.adviser-user=adviser", "app.adviser-password=test-adviser-password",
        "app.policy.poll-ms=3600000"
})
@AutoConfigureMockMvc
class PolicyServiceTest {
    @Autowired PolicyService policies;
    @Autowired CaseService cases;
    @Autowired PolicySimulator simulator;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean Clock clock;

    @BeforeEach void time() { advance(Instant.parse("2026-11-08T12:00:00Z")); }
    void advance(Instant time) {
        when(clock.instant()).thenReturn(time);
        when(clock.millis()).thenReturn(time.toEpochMilli());
    }
    CaseView newCase() {
        return cases.create(new Profile("Synthetic MQ test", 38, Employment.SELF_EMPLOYED, 2,
                new BigDecimal("3200.50"), null, null, "Customer-declared facts."), "adviser");
    }
    RequestView latest(long id) { return policies.workspace(id, "IN_PROCESS_DEMO").requests().getFirst(); }
    WireReply reply(RequestView request) {
        return simulator.respond(new WireRequest(request.id(), 1, request.fixture()));
    }

    @Test void correlatedReplyIsSeparateFromDeclaredProfileAndHistory() {
        var c = newCase();
        var request = policies.request(c.id(), new RequestInput(1, Fixture.SOLE_TRADER), "adviser");
        policies.published(request.id());
        policies.accept(reply(request));
        assertEquals(RequestStatus.COMPLETED, latest(c.id()).status());
        assertEquals(new BigDecimal("150.00"), latest(c.id()).snapshot().monthlyPension());
        assertEquals(c, cases.detail(c.id()).caseData());
        assertEquals(1, cases.detail(c.id()).history().size());
        assertEquals("adviser", latest(c.id()).requestedBy());
    }

    @Test void repeatedPendingCommandReturnsSameRequestAndDifferentFixtureConflicts() {
        var c = newCase();
        var first = policies.request(c.id(), new RequestInput(1, Fixture.EMPLOYEE), "adviser");
        assertEquals(first.id(), policies.request(c.id(), new RequestInput(1, Fixture.EMPLOYEE), "adviser").id());
        assertThrows(ResponseStatusException.class,
                () -> policies.request(c.id(), new RequestInput(1, Fixture.SOLE_TRADER), "adviser"));
    }

    @Test void concurrentRequestsCreateOnlyOneActiveRecord() throws Exception {
        var c = newCase();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<RequestView> task = () -> policies.request(c.id(), new RequestInput(1, Fixture.EMPLOYEE), "adviser");
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertEquals(first.get(10, TimeUnit.SECONDS).id(), second.get(10, TimeUnit.SECONDS).id());
        }
        assertEquals(1, policies.workspace(c.id(), "IN_PROCESS_DEMO").requests().size());
    }

    @Test void duplicateReplyDoesNotReplaceTheFirstSnapshot() {
        var c = newCase();
        var request = policies.request(c.id(), new RequestInput(1, Fixture.EMPLOYEE), "adviser");
        var response = reply(request);
        policies.accept(response);
        policies.accept(new WireReply(response.requestId(), 1, response.fixture(),
                new Snapshot("FICTIONAL_POLICY_SIMULATOR", "DEMO-EMPLOYEE-001", BigDecimal.ZERO, null, "Duplicate")));
        assertEquals(response.snapshot(), latest(c.id()).snapshot());
    }

    @Test void editedProfileMakesIncomingAndPreviouslyCompletedRepliesStale() {
        var c = newCase();
        var request = policies.request(c.id(), new RequestInput(1, Fixture.SOLE_TRADER), "adviser");
        cases.update(c.id(), new ProfileUpdate(1, c.profile()), "adviser");
        policies.accept(reply(request));
        assertEquals(RequestStatus.STALE, latest(c.id()).status());
        var second = policies.request(c.id(), new RequestInput(2, Fixture.SOLE_TRADER), "adviser");
        policies.accept(reply(second));
        assertEquals(RequestStatus.COMPLETED, latest(c.id()).status());
        cases.update(c.id(), new ProfileUpdate(2, c.profile()), "adviser");
        assertEquals(RequestStatus.STALE, latest(c.id()).status());
    }

    @Test void deadlineAndLateRepliesDoNotResurrectRequest() {
        var c = newCase();
        var request = policies.request(c.id(), new RequestInput(1, Fixture.CAREER_CHANGE), "adviser");
        advance(Instant.parse("2026-11-08T12:01:00Z"));
        policies.accept(reply(request));
        assertEquals(RequestStatus.TIMED_OUT, latest(c.id()).status());
        assertNull(latest(c.id()).snapshot());
        assertTrue(policies.pending().stream().noneMatch(item -> item.requestId().equals(request.id())));
        assertNotEquals(request.id(), policies.request(c.id(), new RequestInput(1, Fixture.CAREER_CHANGE), "adviser").id());
    }

    @Test void malformedSchemaOrFixtureFailsExplicitly() {
        var c = newCase();
        var request = policies.request(c.id(), new RequestInput(1, Fixture.EMPLOYEE), "adviser");
        policies.accept(new WireReply(request.id(), 99, Fixture.SOLE_TRADER, reply(request).snapshot()));
        assertEquals(RequestStatus.FAILED, latest(c.id()).status());
        assertNotNull(latest(c.id()).lastError());
        assertNull(latest(c.id()).snapshot());
    }

    @Test void mismatchedPolicyReferenceAndInvalidAmountsAreRejected() {
        var c = newCase();
        var first = policies.request(c.id(), new RequestInput(1, Fixture.EMPLOYEE), "adviser");
        policies.accept(new WireReply(first.id(), 1, Fixture.EMPLOYEE,
                new Snapshot("FICTIONAL_POLICY_SIMULATOR", "DEMO-TRADER-001", BigDecimal.ZERO, null, "Wrong reference")));
        assertEquals(RequestStatus.FAILED, latest(c.id()).status());
        var second = policies.request(c.id(), new RequestInput(1, Fixture.EMPLOYEE), "adviser");
        policies.accept(new WireReply(second.id(), 1, Fixture.EMPLOYEE,
                new Snapshot("FICTIONAL_POLICY_SIMULATOR", "DEMO-EMPLOYEE-001", new BigDecimal("-1"), null, "Invalid amount")));
        assertEquals(RequestStatus.FAILED, latest(c.id()).status());
        assertNull(latest(c.id()).snapshot());
    }

    @Test void brokerFailureRetainsRequestAndRecoveryClearsWarning() {
        var c = newCase();
        var request = policies.request(c.id(), new RequestInput(1, Fixture.SOLE_TRADER), "adviser");
        policies.publicationFailed(request.id());
        assertEquals(RequestStatus.QUEUED, latest(c.id()).status());
        assertEquals(1, latest(c.id()).attempts());
        assertNotNull(latest(c.id()).lastError());
        policies.published(request.id());
        policies.accept(reply(request));
        assertEquals(RequestStatus.COMPLETED, latest(c.id()).status());
        assertNull(latest(c.id()).lastError());
    }

    @Test void staleCaseRevisionAndClosedCaseRejectRequest() {
        var c = newCase();
        assertThrows(ResponseStatusException.class, () -> policies.request(c.id(), new RequestInput(9, Fixture.EMPLOYEE), "adviser"));
        cases.transition(c.id(), new StatusUpdate(1, Status.IN_REVIEW), "adviser");
        cases.transition(c.id(), new StatusUpdate(2, Status.CLOSED), "adviser");
        assertThrows(ResponseStatusException.class, () -> policies.request(c.id(), new RequestInput(3, Fixture.EMPLOYEE), "adviser"));
    }

    @Test void apiRequiresSessionCsrfAndValidFixture() throws Exception {
        var c = newCase();
        mvc.perform(get("/api/cases/" + c.id() + "/policy")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/cases/" + c.id() + "/policy/requests").with(user("adviser"))
                .contentType("application/json").content("{\"revision\":1,\"fixture\":\"EMPLOYEE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/cases/" + c.id() + "/policy/requests").with(user("adviser")).with(csrf())
                .contentType("application/json").content("{\"revision\":1,\"fixture\":\"REAL-CUSTOMER\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/cases/" + c.id() + "/policy/requests").with(user("adviser")).with(csrf())
                .contentType("application/json").content("{\"revision\":1,\"fixture\":\"EMPLOYEE\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test void inProcessTransportRetainsMessageWhenHandlerFails() {
        var port = new DemoPolicyAdapter();
        var request = new WireRequest(java.util.UUID.randomUUID(), 1, Fixture.CAREER_CHANGE);
        port.send(request);
        assertThrows(IllegalStateException.class, () -> port.consumeRequest(value -> { throw new IllegalStateException("Test rollback"); }));
        assertTrue(port.consumeRequest(value -> assertEquals(request, value)));
        assertFalse(port.consumeRequest(value -> fail("Queue should be empty")));
    }
}
