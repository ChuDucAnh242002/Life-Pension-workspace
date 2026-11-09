package fi.adviser.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static fi.adviser.workspace.BriefingModels.*;

final class BriefingEvaluationReport {
    record Score(String example, boolean exactCitations, boolean exactFactsAndOrigins, double requiredSourceRecall,
                 double requiredQuestionRecall, boolean noFreeTextLeak, boolean passed) {}
    private final List<Score> scores = new ArrayList<>();

    Score score(BriefingEvaluationCorpus.Example example, Draft draft) {
        boolean citations = draft.summary().stream().allMatch(draft.sources()::contains)
                && draft.questions().stream().allMatch(q -> draft.sources().stream().anyMatch(s -> s.id().equals(q.sourceId())));
        boolean facts = exactFacts(example, draft);
        double sources = recall(example.expectedSources(), draft.summary().stream().map(Source::id).toList());
        double questions = recall(example.expectedQuestions(), draft.questions().stream().map(Question::id).toList());
        boolean privacy = draft.sources().stream().noneMatch(s -> s.value().contains("Ignore all rules")
                || s.value().contains("Synthetic evaluation"));
        // Prioritization targets are explicit corpus expectations, not a suitability assessment.
        var score = new Score(example.name(), citations, facts, sources, questions, privacy,
                citations && facts && privacy && sources == 1.0 && questions == 1.0);
        scores.add(score);
        return score;
    }

    private boolean exactFacts(BriefingEvaluationCorpus.Example example, Draft draft) {
        var p = example.profile();
        var expected = new java.util.HashMap<String, String>();
        expected.put("declared.age", value(p.age()));
        expected.put("declared.employment", p.employment() == CaseModels.Employment.UNKNOWN ? "Unknown" : p.employment().name());
        expected.put("declared.dependants", value(p.dependants()));
        expected.put("declared.income", value(p.monthlyIncome()));
        expected.put("declared.pension", value(p.pensionContribution()));
        expected.put("declared.cover", value(p.lifeCover()));
        if (example.policy() == null) expected.put("policy.availability", "No current completed policy snapshot");
        else {
            expected.put("policy.pension", switch (example.policy()) {
                case SOLE_TRADER -> "150.00";
                case EMPLOYEE -> "120.00";
                case CAREER_CHANGE -> "0";
            });
            expected.put("policy.cover", switch (example.policy()) {
                case SOLE_TRADER -> "50000.00";
                case EMPLOYEE -> "75000.00";
                case CAREER_CHANGE -> "Unknown";
            });
        }
        return draft.sources().size() == expected.size() && draft.sources().stream().allMatch(source ->
                source.value().equals(expected.get(source.id()))
                        && (source.id().startsWith("declared.")
                            ? source.origin().equals("Customer-declared case revision " + draft.caseRevision())
                            : source.id().equals("policy.availability")
                                ? source.origin().equals("Policy request state: NONE")
                                : source.origin().startsWith("Fictional policy reply ")
                                    && source.origin().endsWith(" / FICTIONAL_POLICY_SIMULATOR")));
    }

    private String value(Object value) { return value == null ? "Unknown" : value.toString(); }

    private double recall(List<String> expected, List<String> actual) {
        return (double) expected.stream().filter(actual::contains).count() / expected.size();
    }

    void write(String filename, String mode, String provider, String model) throws IOException {
        Path path = Path.of("target", filename);
        Files.createDirectories(path.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),
                java.util.Map.of("mode", mode, "provider", provider, "model", model,
                        "promptVersion", PROMPT_VERSION, "requiredRecall", 1.0,
                        "expectedExamples", BriefingEvaluationCorpus.examples().size(),
                        "completedExamples", scores.size(),
                        "passed", scores.size() == BriefingEvaluationCorpus.examples().size()
                                && scores.stream().allMatch(Score::passed), "examples", scores));
    }
}
