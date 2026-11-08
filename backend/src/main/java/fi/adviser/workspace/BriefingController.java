package fi.adviser.workspace;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import static fi.adviser.workspace.BriefingModels.*;

@RestController
@RequestMapping("/api/cases/{caseId}/briefing")
public class BriefingController {
    private final BriefingService service;
    public BriefingController(BriefingService service) { this.service = service; }
    @GetMapping
    public View view(@PathVariable long caseId) { return service.view(caseId); }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Draft generate(@PathVariable long caseId, @Valid @RequestBody Input input, Principal actor) {
        return service.generate(caseId, input, actor.getName());
    }
    @PostMapping("/approval")
    public Approval approve(@PathVariable long caseId, @Valid @RequestBody ApprovalInput input, Principal actor) {
        return service.approve(caseId, input, actor.getName());
    }
    @GetMapping("/approved")
    public Draft approved(@PathVariable long caseId) { return service.approved(caseId); }
}
