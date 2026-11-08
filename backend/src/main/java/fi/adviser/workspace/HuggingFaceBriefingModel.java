package fi.adviser.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import static fi.adviser.workspace.BriefingModels.*;

@Component
@ConditionalOnProperty(name = "app.ai.backend", havingValue = "huggingface", matchIfMissing = true)
public class HuggingFaceBriefingModel implements BriefingModel {
    private final boolean enabled;
    private final String provider;
    private final String model;
    private final String token;
    private final URI endpoint;
    private final BriefingHttp http;

    @Autowired
    public HuggingFaceBriefingModel(ObjectMapper mapper,
            @Value("${app.ai.enabled:false}") boolean enabled,
            @Value("${app.ai.provider:}") String provider,
            @Value("${app.ai.model:}") String model,
            @Value("${app.ai.token-file:}") String tokenFile) throws IOException {
        this(mapper, enabled, provider, model,
                enabled && !tokenFile.isBlank() ? Files.readString(Path.of(tokenFile)).strip() : "",
                endpoint(enabled, provider));
    }

    HuggingFaceBriefingModel(ObjectMapper mapper, boolean enabled, String provider, String model,
                            String token, URI endpoint) {
        if (enabled && (model.isBlank() || model.contains(":") || token.isBlank() || token.contains("\n") || token.contains("\r"))) {
            throw new IllegalArgumentException("AI requires an explicit HF model, provider, and private token file.");
        }
        this.enabled = enabled;
        this.provider = provider;
        this.model = model;
        this.token = token;
        this.endpoint = endpoint;
        this.http = new BriefingHttp(mapper);
    }

    static URI endpoint(boolean enabled, String provider) {
        if (enabled && (!provider.matches("[a-z][a-z0-9-]{0,39}") || "auto".equals(provider))) {
            throw new IllegalArgumentException("Configure an explicit HF inference provider, not automatic routing.");
        }
        return URI.create("https://router.huggingface.co/v1/chat/completions");
    }

    public boolean enabled() { return enabled; }
    public String provider() { return provider.isBlank() ? "Not configured" : "Hugging Face / " + provider; }
    public String model() { return model; }
    public Selection select(Context context) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI is disabled. Configure an AI backend before generating a draft.");
        return http.select(context, model + ":" + provider, endpoint, "Authorization", "Bearer " + token, "Hugging Face", "max_tokens");
    }
}
