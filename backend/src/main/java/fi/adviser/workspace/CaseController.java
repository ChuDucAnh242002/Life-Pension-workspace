package fi.adviser.workspace;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import static fi.adviser.workspace.CaseModels.*;

@RestController
@RequestMapping("/api")
public class CaseController {
    private final CaseService cases;
    private final boolean publicDemo;
    public CaseController(CaseService cases, @Value("${app.public-demo:false}") boolean publicDemo) {
        this.cases = cases;
        this.publicDemo = publicDemo;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @GetMapping("/session")
    public Map<String, Object> session(Principal principal) {
        return Map.of("username", principal.getName(), "publicDemo", publicDemo);
    }

    @GetMapping("/cases")
    public List<CaseView> list() { return cases.list(); }

    @GetMapping("/cases/{id}")
    public CaseDetail detail(@PathVariable long id) { return cases.detail(id); }

    @PostMapping("/cases")
    @ResponseStatus(HttpStatus.CREATED)
    public CaseView create(@Valid @RequestBody Profile input, Principal actor) {
        return cases.create(input, actor.getName());
    }

    @PutMapping("/cases/{id}/profile")
    public CaseView update(@PathVariable long id, @Valid @RequestBody ProfileUpdate input, Principal actor) {
        return cases.update(id, input, actor.getName());
    }

    @PostMapping("/cases/{id}/status")
    public CaseView transition(@PathVariable long id, @Valid @RequestBody StatusUpdate input, Principal actor) {
        return cases.transition(id, input, actor.getName());
    }
}
