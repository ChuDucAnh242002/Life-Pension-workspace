package fi.adviser.workspace;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class PolicyModels {
    private PolicyModels() {}
    public enum Fixture { SOLE_TRADER, EMPLOYEE, CAREER_CHANGE }
    public enum RequestStatus { QUEUED, WAITING, COMPLETED, TIMED_OUT, FAILED, STALE }
    public record RequestInput(@Min(1) long revision, @NotNull Fixture fixture) {}
    public record WireRequest(UUID requestId, int schemaVersion, Fixture fixture) {}
    public record Snapshot(String source, String policyReference, BigDecimal monthlyPension,
                           BigDecimal lifeCover, String explanation) {}
    public record WireReply(UUID requestId, int schemaVersion, Fixture fixture, Snapshot snapshot) {}
    public record RequestView(UUID id, long caseId, long caseRevision, Fixture fixture, RequestStatus status,
                              String requestedBy, String createdAt, long expiresAt, String completedAt,
                              int attempts, String lastError, Snapshot snapshot) {}
    public record Workspace(String transport, boolean simulator, List<RequestView> requests) {}
}
