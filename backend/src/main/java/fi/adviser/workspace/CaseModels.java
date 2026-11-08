package fi.adviser.workspace;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public final class CaseModels {
    private CaseModels() {}

    public enum Status { DRAFT, IN_REVIEW, CLOSED }
    public enum Employment { UNKNOWN, EMPLOYED, SELF_EMPLOYED, RETIRED, OTHER }
    public record Profile(
            @NotBlank @Size(max = 100) String customerAlias,
            @Min(18) @Max(100) Integer age,
            @NotNull Employment employment,
            @Min(0) @Max(20) Integer dependants,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal monthlyIncome,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal pensionContribution,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal lifeCover,
            @Size(max = 2000) String notes) {}
    public record CaseView(long id, long revision, Status status, Profile profile, String updatedAt) {}
    public record ProfileUpdate(@Min(1) long revision, @NotNull @Valid Profile profile) {}
    public record StatusUpdate(@Min(1) long revision, @NotNull Status status) {}
    public record Change(long revision, String actor, String action, String changedAt, CaseView snapshot) {}
    public record CaseDetail(CaseView caseData, List<Change> history) {}
}
