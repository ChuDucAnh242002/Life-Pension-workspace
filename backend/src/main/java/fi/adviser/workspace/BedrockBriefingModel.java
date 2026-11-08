package fi.adviser.workspace;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;
import java.io.IOException;
import java.time.Duration;
import static fi.adviser.workspace.BriefingModels.*;

@Component
@ConditionalOnProperty(name = "app.ai.backend", havingValue = "bedrock")
public class BedrockBriefingModel implements BriefingModel {
    private static final Logger log = LoggerFactory.getLogger(BedrockBriefingModel.class);
    private final boolean enabled;
    private final String region;
    private final String model;
    private final BriefingProtocol protocol;
    private final BedrockRuntimeClient client;
    private final DefaultCredentialsProvider credentials;

    @Autowired
    public BedrockBriefingModel(BriefingProtocol protocol,
            @Value("${app.ai.enabled:false}") boolean enabled,
            @Value("${app.ai.bedrock.region:}") String region,
            @Value("${app.ai.bedrock.model:}") String model) {
        if (enabled && (region.isBlank() || model.isBlank())) {
            throw new IllegalArgumentException("Bedrock requires an explicit region and model/inference-profile ID.");
        }
        this.enabled = enabled;
        this.region = region;
        this.model = model;
        this.protocol = protocol;
        this.credentials = enabled ? DefaultCredentialsProvider.builder().build() : null;
        this.client = enabled ? BedrockRuntimeClient.builder().region(Region.of(region))
                .credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(125)))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(130))
                        .apiCallAttemptTimeout(Duration.ofSeconds(125))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()))
                .build() : null;
    }

    BedrockBriefingModel(BriefingProtocol protocol, String region, String model, BedrockRuntimeClient client) {
        this.protocol = protocol;
        this.enabled = true;
        this.region = region;
        this.model = model;
        this.client = client;
        this.credentials = null;
    }

    public boolean enabled() { return enabled; }
    public String provider() { return "AWS Bedrock / " + region; }
    public String model() { return model; }
    public Selection select(Context context) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI is disabled. Configure an AI backend before generating a draft.");
        try {
            var request = ConverseRequest.builder().modelId(model)
                    .system(SystemContentBlock.builder().text(BriefingProtocol.SYSTEM).build())
                    .messages(Message.builder().role(ConversationRole.USER)
                            .content(ContentBlock.fromText(protocol.context(context))).build())
                    .inferenceConfig(InferenceConfiguration.builder().maxTokens(500).build())
                    .outputConfig(OutputConfig.builder().textFormat(OutputFormat.builder().type("json_schema")
                            .structure(OutputFormatStructure.builder().jsonSchema(JsonSchemaDefinition.builder()
                                    .name("meeting_briefing").schema(protocol.schemaJson(context)).build()).build())
                            .build()).build()).build();
            var response = client.converse(request);
            if (response.stopReason() != StopReason.END_TURN || response.output() == null
                    || response.output().message() == null) throw new IOException("Incomplete Bedrock response.");
            var content = response.output().message().content();
            var text = content.stream().filter(block -> block.text() != null).toList();
            if (text.size() != 1 || text.getFirst().text().length() > 65536
                    || content.stream().anyMatch(block -> block.text() == null && block.reasoningContent() == null)) {
                throw new IOException("Invalid Bedrock response.");
            }
            return protocol.parse(text.getFirst().text());
        } catch (BedrockRuntimeException failure) {
            log.warn("Bedrock briefing request rejected; HTTP status {}.", failure.statusCode());
            String reason = switch (failure.statusCode()) {
                case 401, 403 -> "Check AWS credentials, bedrock:InvokeModel permission, and model access.";
                case 400, 404, 422 -> "Check region, model/inference-profile ID, and structured-output support.";
                case 429 -> "Rate limit reached. Wait before trying again.";
                default -> "Provider unavailable. Try again later.";
            };
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Bedrock inference failed (HTTP " + failure.statusCode() + "). " + reason + " No draft was saved.");
        } catch (SdkClientException failure) {
            log.warn("Bedrock client failed: {}.", failure.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Bedrock credentials could not be resolved, or the request timed out/failed. Check the AWS credential chain and network. No draft was saved.");
        } catch (IOException failure) {
            log.warn("Bedrock response/JSON failed: {}.", failure.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Bedrock returned incomplete or invalid JSON. No draft was saved.");
        }
    }

    @PreDestroy
    void close() {
        if (client != null) client.close();
        if (credentials != null) credentials.close();
    }
}
