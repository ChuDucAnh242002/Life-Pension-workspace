package fi.adviser.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.concurrent.*;
import static fi.adviser.workspace.CaseModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cases;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=test",
        "app.adviser-user=adviser", "app.adviser-password=test-adviser-password"
})
@AutoConfigureMockMvc
class CaseWorkspaceTest {
    @Autowired CaseService cases;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;

    private Profile profile() {
        return new Profile("Synthetic test case", 38, Employment.SELF_EMPLOYED, 2,
                new BigDecimal("3200.50"), null, BigDecimal.ZERO, "Declared only.");
    }

    @Test void migrationsLoadSyntheticCasesAndUnknownIsNotZero() {
        var fixtures = cases.list().stream().filter(c -> c.profile().customerAlias().startsWith("Synthetic:")).toList();
        assertEquals(3, fixtures.size());
        var trader = fixtures.stream().filter(c -> c.profile().employment() == Employment.SELF_EMPLOYED).findFirst().orElseThrow();
        assertNull(trader.profile().pensionContribution());
        var change = fixtures.stream().filter(c -> c.profile().employment() == Employment.OTHER).findFirst().orElseThrow();
        assertEquals(0, change.profile().pensionContribution().signum());
    }

    @Test void creatingCaseRecordsActorAndSnapshot() {
        var created = cases.create(profile(), "adviser");
        var detail = cases.detail(created.id());
        assertEquals(Status.DRAFT, created.status());
        assertEquals(1, created.revision());
        assertEquals("adviser", detail.history().getFirst().actor());
        assertEquals(created, detail.history().getFirst().snapshot());
    }

    @Test void editingReviewedProfileReturnsToDraftAndRetainsHistoricalSnapshot() {
        var created = cases.create(profile(), "adviser");
        var review = cases.transition(created.id(), new StatusUpdate(1, Status.IN_REVIEW), "adviser");
        var changed = new Profile("Updated synthetic alias", 40, Employment.EMPLOYED, 0, null, BigDecimal.ZERO, null, null);
        var result = cases.update(created.id(), new ProfileUpdate(review.revision(), changed), "adviser");
        assertEquals(Status.DRAFT, result.status());
        assertNull(result.profile().monthlyIncome());
        assertEquals(3, cases.detail(created.id()).history().size());
        assertEquals("Synthetic test case", cases.detail(created.id()).history().getLast().snapshot().profile().customerAlias());
    }

    @Test void staleProfileCannotOverwriteChangesOrAddHistory() {
        var c = cases.create(profile(), "adviser");
        cases.update(c.id(), new ProfileUpdate(1, profile()), "adviser");
        assertThrows(ResponseStatusException.class, () -> cases.update(c.id(), new ProfileUpdate(1, profile()), "adviser"));
        assertEquals(2, cases.detail(c.id()).history().size());
    }

    @Test void workflowRejectsSkippingReviewAndRequiresReopeningClosedCases() {
        var c = cases.create(profile(), "adviser");
        assertThrows(ResponseStatusException.class, () -> cases.transition(c.id(), new StatusUpdate(1, Status.CLOSED), "adviser"));
        cases.transition(c.id(), new StatusUpdate(1, Status.IN_REVIEW), "adviser");
        cases.transition(c.id(), new StatusUpdate(2, Status.CLOSED), "adviser");
        assertThrows(ResponseStatusException.class, () -> cases.update(c.id(), new ProfileUpdate(3, profile()), "adviser"));
        var reopened = cases.transition(c.id(), new StatusUpdate(3, Status.DRAFT), "adviser");
        assertEquals(4, reopened.revision());
    }

    @Test void caseAndHistoryRollbackTogether() {
        long before = jdbc.queryForObject("SELECT count(*) FROM adviser_case", Long.class);
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            cases.create(profile(), "adviser");
            status.setRollbackOnly();
        });
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM adviser_case", Long.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM case_change h LEFT JOIN adviser_case c ON c.id=h.case_id WHERE c.id IS NULL
                """, Integer.class));
    }

    @Test void concurrentWritersCannotBothUseTheSameRevision() throws Exception {
        var c = cases.create(profile(), "adviser");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> update = () -> {
                start.await();
                try { cases.update(c.id(), new ProfileUpdate(1, profile()), "adviser"); return true; }
                catch (ResponseStatusException conflict) { return false; }
            };
            var first = executor.submit(update);
            var second = executor.submit(update);
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(2, cases.detail(c.id()).history().size());
        assertEquals(2, cases.detail(c.id()).caseData().revision());
    }

    @Test void apiRequiresAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/api/cases")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cases").with(user("adviser"))).andExpect(status().isOk());
        mvc.perform(post("/api/cases").with(user("adviser"))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(profile())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/cases").with(user("adviser")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(profile())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.profile.lifeCover").value(0));
    }

    @Test void invalidAndUnknownFieldsAreRejectedWithoutWriting() throws Exception {
        String json = mapper.writeValueAsString(profile());
        for (String invalid : new String[] {
                json.replace("3200.50", "-1"), json.replace("3200.50", "1.234"),
                json.replace("\"age\":38", "\"age\":17"),
                json.replace("Synthetic test case", "   "),
                json.replace("\"SELF_EMPLOYED\"", "\"WRONG\""),
                json.substring(0, json.length() - 1) + ",\"verified\":true}"
        }) {
            mvc.perform(post("/api/cases").with(user("adviser")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
    }

    @Test void nonexistentCaseAndStaleStatusHaveExplicitErrors() throws Exception {
        mvc.perform(get("/api/cases/999999").with(user("adviser"))).andExpect(status().isNotFound());
        var c = cases.create(profile(), "adviser");
        mvc.perform(post("/api/cases/" + c.id() + "/status").with(user("adviser")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":99,\"status\":\"IN_REVIEW\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test void configuredCredentialsSignInAndWrongPasswordFails() throws Exception {
        mvc.perform(post("/api/login").with(csrf()).param("username", "adviser").param("password", "test-adviser-password"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/login").with(csrf()).param("username", "adviser").param("password", "wrong"))
                .andExpect(status().isUnauthorized());
    }
}
