package fi.adviser.workspace;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import static fi.adviser.workspace.PolicyModels.*;

@Component
public class PolicySimulator {
    public WireReply respond(WireRequest request) {
        if (request.requestId() == null || request.schemaVersion() != 1 || request.fixture() == null) {
            throw new IllegalArgumentException("Unsupported policy request schema.");
        }
        Snapshot result = switch (request.fixture()) {
            case SOLE_TRADER -> new Snapshot("FICTIONAL_POLICY_SIMULATOR", "DEMO-TRADER-001",
                    new BigDecimal("150.00"), new BigDecimal("50000.00"),
                    "Fictional voluntary pension contribution and life cover. Not a verified customer policy.");
            case EMPLOYEE -> new Snapshot("FICTIONAL_POLICY_SIMULATOR", "DEMO-EMPLOYEE-001",
                    new BigDecimal("120.00"), new BigDecimal("75000.00"),
                    "Fictional employee protection package. Values are demonstration data, not suitability advice.");
            case CAREER_CHANGE -> new Snapshot("FICTIONAL_POLICY_SIMULATOR", "DEMO-CAREER-001",
                    BigDecimal.ZERO, null,
                    "Fictional record declares zero voluntary pension contribution; life cover is unknown, not zero.");
        };
        return new WireReply(request.requestId(), 1, request.fixture(), result);
    }
}
