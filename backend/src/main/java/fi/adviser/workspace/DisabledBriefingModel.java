package fi.adviser.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import static fi.adviser.workspace.BriefingModels.*;

@Component
@ConditionalOnProperty(name = "app.ai.backend", havingValue = "none", matchIfMissing = true)
class DisabledBriefingModel implements BriefingModel {
    DisabledBriefingModel(@Value("${app.ai.enabled:false}") boolean enabled) {
        if (enabled) {
            throw new IllegalArgumentException("AI_ENABLED requires AI_BACKEND=azure or AI_BACKEND=bedrock.");
        }
    }

    public boolean enabled() { return false; }
    public String provider() { return "Not configured"; }
    public String model() { return ""; }

    public Selection select(Context context) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI is disabled. Configure an AI backend before generating a draft.");
    }
}
