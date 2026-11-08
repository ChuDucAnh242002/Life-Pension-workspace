package fi.adviser.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import static fi.adviser.workspace.BriefingModels.*;

@Service
public class BriefingService {
    private static final Logger log = LoggerFactory.getLogger(BriefingService.class);
    private final CaseService cases;
    private final PolicyService policies;
    private final BriefingModel model;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public BriefingService(CaseService cases, PolicyService policies, BriefingModel model, JdbcTemplate jdbc,
                           ObjectMapper mapper, Clock clock, PlatformTransactionManager transactions) {
        this.cases = cases;
        this.policies = policies;
        this.model = model;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }

    public View view(long caseId) {
        return transaction.execute(status -> {
            var context = context(caseId, false);
            var saved = jdbc.query("SELECT snapshot FROM briefing_draft WHERE case_id=? ORDER BY draft_order DESC FETCH FIRST 1 ROWS ONLY",
                    (rs, index) -> decode(rs.getString("snapshot")), caseId);
            Draft draft = saved.isEmpty() ? null : saved.getFirst();
            return new View(model.enabled(), model.provider(), model.model(),
                    draft != null && !sameBasis(draft, context), draft);
        });
    }

    public Draft generate(long caseId, Input input, String actor) {
        Context basis = transaction.execute(status -> {
            var context = context(caseId, true);
            if (context.caseRevision() != input.revision()) throw changed();
            return context;
        });
        // Never hold a database lock during a paid external inference request.
        var selection = model.select(basis);
        validate(selection, basis);
        var summary = selection.summarySourceIds().stream()
                .map(id -> basis.sources().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow()).toList();
        var questions = selection.questionIds().stream()
                .map(id -> basis.questions().stream().filter(q -> q.id().equals(id)).findFirst().orElseThrow()).toList();
        var draft = new Draft(UUID.randomUUID().toString(), basis.caseRevision(), basis.policyBasis(),
                clock.instant().toString(), actor, model.provider(), model.model(),
                PROMPT_VERSION, summary, questions, basis.sources());
        return transaction.execute(status -> {
            if (!sameBasis(draft, context(caseId, true))) throw changed();
            try {
                jdbc.update("INSERT INTO briefing_draft(id,case_id,snapshot) VALUES (?,?,?)",
                        draft.id(), caseId, mapper.writeValueAsString(draft));
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("Could not encode briefing draft.", failure);
            }
            return draft;
        });
    }

    private Context context(long caseId, boolean generating) {
        var locked = jdbc.queryForList("SELECT id FROM adviser_case WHERE id=? FOR UPDATE", caseId);
        if (locked.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found.");
        var data = cases.detail(caseId).caseData();
        if (generating && data.status() == CaseModels.Status.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reopen the case before generating a briefing.");
        }
        var p = data.profile();
        var sources = new ArrayList<Source>();
        var questions = new ArrayList<Question>();
        String origin = "Customer-declared case revision " + data.revision();
        add(sources, questions, "age", "Age", p.age(), origin, "Confirm the customer's age.");
        add(sources, questions, "employment", "Employment",
                p.employment() == CaseModels.Employment.UNKNOWN ? null : p.employment(), origin, "Clarify current employment.");
        add(sources, questions, "dependants", "Dependants", p.dependants(), origin, "Clarify the number of dependants.");
        add(sources, questions, "income", "Monthly income (EUR)", p.monthlyIncome(), origin, "Clarify monthly income.");
        add(sources, questions, "pension", "Declared monthly pension contribution (EUR)", p.pensionContribution(),
                origin, "Clarify the declared voluntary pension contribution.");
        add(sources, questions, "cover", "Declared life cover (EUR)", p.lifeCover(), origin, "Clarify declared life cover.");
        // Alias, free-text notes, history and adviser identity never cross the inference boundary.
        questions.add(new Question("confirm_declared", "Confirm that the declared facts are still current.", "declared.age"));
        var requests = policies.workspace(caseId, "").requests();
        var latest = requests.isEmpty() ? null : requests.getFirst();
        String policyBasis = latest == null ? "NONE" : latest.id() + ":" + latest.status();
        if (latest != null && latest.status() == PolicyModels.RequestStatus.COMPLETED) {
            var snapshot = latest.snapshot();
            String policyOrigin = "Fictional policy reply " + latest.id() + " / " + snapshot.source();
            add(sources, questions, "policy_pension", "Fictional policy monthly pension (EUR)",
                    snapshot.monthlyPension(), policyOrigin, "Clarify the fictional policy contribution.");
            add(sources, questions, "policy_cover", "Fictional policy life cover (EUR)",
                    snapshot.lifeCover(), policyOrigin, "Clarify missing fictional policy cover.");
            questions.add(new Question("confirm_policy", "Confirm the fictional fixture selection; it is not a verified customer-policy link.",
                    "policy.pension"));
        } else {
            sources.add(new Source("policy.availability", "Policy information", "No current completed policy snapshot",
                    "Policy request state: " + (latest == null ? "NONE" : latest.status())));
            questions.add(new Question("retrieve_policy", "Request a current fictional policy snapshot if relevant to the meeting.",
                    "policy.availability"));
        }
        return new Context(data.revision(), policyBasis, List.copyOf(sources), List.copyOf(questions));
    }

    private void add(List<Source> sources, List<Question> questions, String field, String label,
                     Object value, String origin, String question) {
        String id = field.startsWith("policy_") ? "policy." + field.substring(7) : "declared." + field;
        sources.add(new Source(id, label, value == null ? "Unknown" : value.toString(), origin));
        if (value == null) questions.add(new Question("clarify_" + field, question, id));
    }

    private void validate(Selection selection, Context context) {
        if (selection == null
                || !validIds(selection.summarySourceIds(), context.sources().stream().map(Source::id).toList())
                || !validIds(selection.questionIds(), context.questions().stream().map(Question::id).toList())) {
            log.warn("Rejecting AI briefing with missing, duplicate, excessive or unknown source/question IDs.");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI returned invalid source citations. No draft was saved.");
        }
    }

    private boolean validIds(List<String> ids, List<String> allowed) {
        return ids != null && !ids.isEmpty() && ids.size() <= 8
                && new HashSet<>(ids).size() == ids.size() && allowed.containsAll(ids);
    }

    private boolean sameBasis(Draft draft, Context context) {
        return draft.caseRevision() == context.caseRevision() && draft.policyBasis().equals(context.policyBasis());
    }

    private ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Case or policy sources changed. Reload and generate a new draft.");
    }

    private Draft decode(String json) {
        try { return mapper.readValue(json, Draft.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Could not read briefing draft.", failure); }
    }
}
