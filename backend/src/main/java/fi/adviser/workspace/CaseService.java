package fi.adviser.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import static fi.adviser.workspace.CaseModels.*;

@Service
public class CaseService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CaseService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<CaseView> list() {
        return jdbc.query("SELECT * FROM adviser_case ORDER BY id", this::row);
    }

    private CaseView get(long id) {
        return jdbc.query("SELECT * FROM adviser_case WHERE id=?", this::row, id).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found."));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE)
    public CaseDetail detail(long id) {
        var data = get(id);
        var history = jdbc.query("SELECT * FROM case_change WHERE case_id=? ORDER BY revision DESC",
                (rs, index) -> new Change(rs.getLong("revision"), rs.getString("actor"),
                        rs.getString("action"), rs.getString("changed_at"), decode(rs.getString("snapshot"))), id);
        return new CaseDetail(data, history);
    }

    @Transactional
    public CaseView create(Profile input, String actor) {
        var profile = normalize(input);
        String time = Instant.now().toString();
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO adviser_case (revision,status,customer_alias,age,employment,dependants,
                      monthly_income,pension_contribution,life_cover,notes,updated_at)
                    VALUES (1,'DRAFT',?,?,?,?,?,?,?,?,?)
                    """, new String[]{"ID"});
            Object[] values = { profile.customerAlias(), profile.age(), profile.employment().name(),
                    profile.dependants(), profile.monthlyIncome(), profile.pensionContribution(),
                    profile.lifeCover(), profile.notes(), time };
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("Database did not return a case ID.");
        CaseView result = get(key.getKey().longValue());
        audit(result, actor, "CASE_CREATED");
        return result;
    }

    @Transactional
    public CaseView update(long id, ProfileUpdate input, String actor) {
        CaseView current = get(id);
        checkRevision(current, input.revision());
        if (current.status() == Status.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reopen the case before editing its profile.");
        }
        var p = normalize(input.profile());
        // Editing reviewed facts invalidates the current review and returns the case to draft.
        int changed = jdbc.update("""
                UPDATE adviser_case SET revision=revision+1,status='DRAFT',customer_alias=?,age=?,
                  employment=?,dependants=?,monthly_income=?,pension_contribution=?,life_cover=?,notes=?,updated_at=?
                WHERE id=? AND revision=?
                """, p.customerAlias(), p.age(), p.employment().name(), p.dependants(), p.monthlyIncome(),
                p.pensionContribution(), p.lifeCover(), p.notes(), Instant.now().toString(), id, input.revision());
        checkChanged(changed);
        var result = get(id);
        audit(result, actor, "PROFILE_UPDATED");
        return result;
    }

    @Transactional
    public CaseView transition(long id, StatusUpdate input, String actor) {
        var current = get(id);
        checkRevision(current, input.revision());
        boolean allowed = switch (current.status()) {
            case DRAFT -> input.status() == Status.IN_REVIEW;
            case IN_REVIEW -> input.status() == Status.DRAFT || input.status() == Status.CLOSED;
            case CLOSED -> input.status() == Status.DRAFT;
        };
        if (!allowed) throw new ResponseStatusException(HttpStatus.CONFLICT, "This status transition is not allowed.");
        int changed = jdbc.update("""
                UPDATE adviser_case SET status=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?
                """, input.status().name(), Instant.now().toString(), id, input.revision());
        checkChanged(changed);
        var result = get(id);
        audit(result, actor, "STATUS_CHANGED");
        return result;
    }

    private void checkRevision(CaseView current, long revision) {
        if (current.revision() != revision) checkChanged(0);
    }

    private void checkChanged(int changed) {
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This case changed in another session. Reload it before trying again.");
    }

    private Profile normalize(Profile p) {
        return new Profile(p.customerAlias().strip(), p.age(), p.employment(), p.dependants(),
                p.monthlyIncome(), p.pensionContribution(), p.lifeCover(),
                p.notes() == null || p.notes().isBlank() ? null : p.notes().strip());
    }

    private void audit(CaseView data, String actor, String action) {
        try {
            jdbc.update("INSERT INTO case_change(case_id,revision,actor,action,changed_at,snapshot) VALUES (?,?,?,?,?,?)",
                    data.id(), data.revision(), actor, action, data.updatedAt(), mapper.writeValueAsString(data));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode case history.", exception);
        }
    }

    private CaseView decode(String json) {
        try {
            return mapper.readValue(json, CaseView.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read case history.", exception);
        }
    }

    private CaseView row(ResultSet rs, int index) throws SQLException {
        return new CaseView(rs.getLong("id"), rs.getLong("revision"), Status.valueOf(rs.getString("status")),
                new Profile(rs.getString("customer_alias"), rs.getObject("age", Integer.class),
                        Employment.valueOf(rs.getString("employment")), rs.getObject("dependants", Integer.class),
                        rs.getBigDecimal("monthly_income"), rs.getBigDecimal("pension_contribution"),
                        rs.getBigDecimal("life_cover"), rs.getString("notes")), rs.getString("updated_at"));
    }
}
