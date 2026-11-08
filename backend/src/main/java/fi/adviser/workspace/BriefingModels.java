package fi.adviser.workspace;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class BriefingModels {
    private BriefingModels() {}
    public static final String PROMPT_VERSION = "source-selection-v1";
    public record Input(@Min(1) long revision) {}
    public record Source(String id, String label, String value, String origin) {}
    public record Question(String id, String text, String sourceId) {}
    public record Context(long caseRevision, String policyBasis, List<Source> sources, List<Question> questions) {}
    public record Selection(List<String> summarySourceIds, List<String> questionIds) {}
    public record Draft(String id, long caseRevision, String policyBasis, String createdAt, String createdBy,
                        String provider, String model, String promptVersion, List<Source> summary,
                        List<Question> questions, List<Source> sources) {}
    public record ApprovalInput(@NotNull UUID draftId, @Min(1) long revision,
                                boolean reviewedSources, @Size(max = 1000) String note) {}
    public enum ApprovalStatus { CURRENT, STALE, SUPERSEDED }
    public record Approval(String draftId, String approvedAt, String approvedBy, String note,
                           ApprovalStatus status) {}
    public record View(boolean enabled, String provider, String model, boolean stale, Draft draft,
                       List<Approval> approvals, boolean usable) {}
}
