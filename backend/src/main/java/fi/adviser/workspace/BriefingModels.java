package fi.adviser.workspace;

import jakarta.validation.constraints.Min;
import java.util.List;

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
    public record View(boolean enabled, String provider, String model, boolean stale, Draft draft) {}
}
