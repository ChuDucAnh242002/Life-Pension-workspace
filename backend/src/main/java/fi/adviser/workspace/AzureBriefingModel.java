package fi.adviser.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
@ConditionalOnProperty(name = "app.ai.backend", havingValue = "azure")
public class AzureBriefingModel implements BriefingModel {
    private final boolean enabled;
    private final String deployment;
    private final String key;
    private final URI endpoint;
    private final BriefingHttp http;

    @Autowired
    public AzureBriefingModel(ObjectMapper mapper,
            @Value("${app.ai.enabled:false}") boolean enabled,
            @Value("${app.ai.azure.endpoint:}") String resource,
            @Value("${app.ai.azure.deployment:}") String deployment,
            @Value("${app.ai.azure.key-file:}") String keyFile) throws IOException {
        this(mapper, enabled, deployment,
                enabled && !keyFile.isBlank() ? Files.readString(Path.of(keyFile)).strip() : "",
                endpoint(enabled, resource));
    }

    AzureBriefingModel(ObjectMapper mapper, boolean enabled, String deployment, String key, URI endpoint) {
        if (enabled && (deployment.isBlank() || key.isBlank() || key.contains("\n") || key.contains("\r"))) {
            throw new IllegalArgumentException("Azure AI requires a deployment and private API-key file.");
        }
        this.enabled = enabled;
        this.deployment = deployment;
        this.key = key;
        this.endpoint = endpoint;
        this.http = new BriefingHttp(mapper);
    }

    static URI endpoint(boolean enabled, String resource) {
        if (!enabled) return URI.create("https://disabled.invalid/openai/v1/chat/completions");
        URI uri = URI.create(resource);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null
                || !(host.endsWith(".openai.azure.com") || host.endsWith(".services.ai.azure.com"))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPort() != -1 || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Use the Azure public-cloud OpenAI resource HTTPS root endpoint, not a partner endpoint.");
        }
        return URI.create("https://" + host + "/openai/v1/chat/completions");
    }

    public boolean enabled() { return enabled; }
    public String provider() { return "Azure OpenAI in Foundry"; }
    public String model() { return deployment; }
    public Selection select(Context context) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI is disabled. Configure an AI backend before generating a draft.");
        return http.select(context, deployment, endpoint, "api-key", key, provider(), "max_completion_tokens");
    }
}
