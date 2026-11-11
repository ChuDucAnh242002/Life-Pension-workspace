package fi.adviser.workspace;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import static fi.adviser.workspace.PolicyModels.*;

@RestController
@RequestMapping("/api/cases/{caseId}/policy")
public class PolicyController {
    private final PolicyService service;
    private final PolicyPort port;
    public PolicyController(PolicyService service, PolicyPort port) { this.service = service; this.port = port; }

    @GetMapping
    public Workspace workspace(@PathVariable long caseId) { return service.workspace(caseId, port.transport()); }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RequestView request(@PathVariable long caseId, @Valid @RequestBody RequestInput input, Principal actor) {
        return service.request(caseId, input, actor.getName());
    }
}
