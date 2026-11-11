package fi.adviser.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static fi.adviser.workspace.PolicyModels.*;

@Service
public class PolicyService {
    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final long timeout;
    private final boolean simulator;

    public PolicyService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
                         @Value("${app.policy.timeout-seconds:60}") long timeout,
                         @Value("${app.policy.simulator:false}") boolean simulator) {
        if (timeout < 5 || timeout > 600) throw new IllegalArgumentException("Policy timeout must be 5-600 seconds.");
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.timeout = timeout;
        this.simulator = simulator;
    }

    public Workspace workspace(long caseId, String transport) {
        caseRevision(caseId);
        expire();
        var requests = jdbc.query("""
                SELECT p.*,c.revision AS current_revision FROM policy_request p
                  JOIN adviser_case c ON c.id=p.case_id WHERE p.case_id=?
                ORDER BY p.request_order DESC FETCH FIRST 10 ROWS ONLY
                """, this::row, caseId);
        return new Workspace(transport, simulator, requests);
    }

    @Transactional
    public RequestView request(long caseId, RequestInput input, String actor) {
        var cases = jdbc.queryForList("SELECT revision,status FROM adviser_case WHERE id=? FOR UPDATE", caseId);
        if (cases.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found.");
        var current = cases.getFirst();
        if (((Number) current.get("REVISION")).longValue() != input.revision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Case changed. Reload it before requesting a policy.");
        }
        if ("CLOSED".equals(current.get("STATUS"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reopen the case before requesting policy information.");
        }
        expire();
        var active = jdbc.query("""
                SELECT p.*,c.revision AS current_revision FROM policy_request p JOIN adviser_case c ON c.id=p.case_id
                WHERE p.case_id=? AND p.status IN ('QUEUED','WAITING')
                """, this::row, caseId);
        if (!active.isEmpty()) {
            var existing = active.getFirst();
            if (existing.caseRevision() == input.revision() && existing.fixture() == input.fixture()) return existing;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A policy request is already pending. Wait for it to finish or time out.");
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO policy_request(id,case_id,case_revision,fixture,status,requested_by,created_at,expires_at)
                VALUES (?,?,?,?,'QUEUED',?,?,?)
                """, id.toString(), caseId, input.revision(), input.fixture().name(), actor,
                now.toString(), now.plusSeconds(timeout).toEpochMilli());
        return get(id);
    }

    public List<WireRequest> pending() {
        return jdbc.query("""
                SELECT id,fixture FROM policy_request WHERE status IN ('QUEUED','WAITING') AND expires_at>?
                ORDER BY request_order FETCH FIRST 10 ROWS ONLY
                """, (rs, index) -> new WireRequest(UUID.fromString(rs.getString("id")), 1,
                Fixture.valueOf(rs.getString("fixture"))), clock.millis());
    }

    public void published(UUID id) {
        jdbc.update("""
                UPDATE policy_request SET status='WAITING',attempts=attempts+1,last_error=NULL
                WHERE id=? AND status IN ('QUEUED','WAITING') AND expires_at>?
                """, id.toString(), clock.millis());
    }

    public void publicationFailed(UUID id) {
        jdbc.update("""
                UPDATE policy_request SET attempts=attempts+1,
                  last_error='Policy transport unavailable. Retrying until the request deadline.'
                WHERE id=? AND status IN ('QUEUED','WAITING')
                """, id.toString());
    }

    public void expire() {
        jdbc.update("""
                UPDATE policy_request SET status='TIMED_OUT',completed_at=?,
                  last_error='Policy request timed out. Check the transport and request again.'
                WHERE status IN ('QUEUED','WAITING') AND expires_at<=?
                """, clock.instant().toString(), clock.millis());
    }

    @Transactional
    public void accept(WireReply reply) {
        if (reply.requestId() == null) throw new IllegalArgumentException("Missing policy correlation ID.");
        var rows = jdbc.queryForList("SELECT case_id FROM policy_request WHERE id=?", reply.requestId().toString());
        if (rows.isEmpty()) {
            log.warn("Ignoring reply for an unknown policy request.");
            return;
        }
        long caseId = ((Number) rows.getFirst().get("CASE_ID")).longValue();
        long revision = jdbc.queryForObject("SELECT revision FROM adviser_case WHERE id=? FOR UPDATE", Long.class, caseId);
        RequestView current = get(reply.requestId());
        if (current.status() != RequestStatus.QUEUED && current.status() != RequestStatus.WAITING) {
            log.info("Ignoring duplicate or late reply for a terminal policy request.");
            return;
        }
        if (clock.millis() >= current.expiresAt()) {
            expire();
            log.info("Ignoring policy reply received after its deadline.");
            return;
        }
        if (!valid(reply, current)) {
            jdbc.update("""
                    UPDATE policy_request SET status='FAILED',completed_at=?,
                      last_error='Policy reply failed schema or correlation validation. Request again after checking the simulator.'
                    WHERE id=?
                    """, clock.instant().toString(), current.id().toString());
            log.warn("Rejected an invalid policy reply; no profile facts were changed.");
            return;
        }
        try {
            jdbc.update("""
                    UPDATE policy_request SET status=?,completed_at=?,snapshot=?,last_error=NULL WHERE id=?
                    """, revision == current.caseRevision() ? "COMPLETED" : "STALE", clock.instant().toString(),
                    mapper.writeValueAsString(reply.snapshot()), current.id().toString());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode policy snapshot.", exception);
        }
    }

    private boolean valid(WireReply reply, RequestView request) {
        var snapshot = reply.snapshot();
        return reply.schemaVersion() == 1 && reply.fixture() == request.fixture() && snapshot != null
                && "FICTIONAL_POLICY_SIMULATOR".equals(snapshot.source())
                && switch (request.fixture()) {
                    case SOLE_TRADER -> "DEMO-TRADER-001".equals(snapshot.policyReference());
                    case EMPLOYEE -> "DEMO-EMPLOYEE-001".equals(snapshot.policyReference());
                    case CAREER_CHANGE -> "DEMO-CAREER-001".equals(snapshot.policyReference());
                }
                && amount(snapshot.monthlyPension()) && amount(snapshot.lifeCover())
                && snapshot.explanation() != null && !snapshot.explanation().isBlank()
                && snapshot.explanation().length() <= 500;
    }

    private boolean amount(java.math.BigDecimal value) {
        return value == null || (value.signum() >= 0 && value.scale() <= 2
                && value.compareTo(new java.math.BigDecimal("999999999999.99")) <= 0);
    }

    private long caseRevision(long caseId) {
        var values = jdbc.queryForList("SELECT revision FROM adviser_case WHERE id=?", Long.class, caseId);
        if (values.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found.");
        return values.getFirst();
    }

    private RequestView get(UUID id) {
        return jdbc.queryForObject("""
                SELECT p.*,c.revision AS current_revision FROM policy_request p
                JOIN adviser_case c ON c.id=p.case_id WHERE p.id=?
                """, this::row, id.toString());
    }

    private RequestView row(ResultSet rs, int index) throws SQLException {
        RequestStatus status = RequestStatus.valueOf(rs.getString("status"));
        if (status == RequestStatus.COMPLETED && rs.getLong("current_revision") != rs.getLong("case_revision")) {
            status = RequestStatus.STALE;
        }
        Snapshot snapshot = null;
        if (rs.getString("snapshot") != null) {
            try { snapshot = mapper.readValue(rs.getString("snapshot"), Snapshot.class); }
            catch (JsonProcessingException exception) { throw new IllegalStateException("Could not read policy snapshot.", exception); }
        }
        return new RequestView(UUID.fromString(rs.getString("id")), rs.getLong("case_id"),
                rs.getLong("case_revision"), Fixture.valueOf(rs.getString("fixture")), status,
                rs.getString("requested_by"), rs.getString("created_at"), rs.getLong("expires_at"),
                rs.getString("completed_at"), rs.getInt("attempts"), rs.getString("last_error"), snapshot);
    }
}
