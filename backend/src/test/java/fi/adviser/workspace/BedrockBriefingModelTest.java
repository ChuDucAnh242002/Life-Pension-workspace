package fi.adviser.workspace;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static fi.adviser.workspace.BriefingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BedrockBriefingModelTest {
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final BriefingProtocol protocol = new BriefingProtocol(mapper);
    private BedrockRuntimeClient client;
    private HttpServer server;
    private int status = 200;
    private String response;
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        response = mapper.writeValueAsString(Map.of("stopReason", "end_turn", "output", Map.of("message",
                Map.of("role", "assistant", "content", List.of(Map.of("text",
                        "{\"summarySourceIds\":[\"declared.age\"],\"questionIds\":[\"confirm_declared\"]}"))))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status != 200) exchange.getResponseHeaders().set("x-amzn-errortype", "ThrottlingException");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        client = BedrockRuntimeClient.builder().region(Region.EU_NORTH_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret")))
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .httpClientBuilder(UrlConnectionHttpClient.builder().socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(c -> c.retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())).build();
    }
    @AfterEach void stop() { client.close(); server.stop(0); }
    private Context context() {
        return new Context(1, "NONE", List.of(new Source("declared.age", "Age", "38", "Declared")),
                List.of(new Question("confirm_declared", "Confirm facts.", "declared.age")));
    }
    private BedrockBriefingModel model() { return new BedrockBriefingModel(protocol, "eu-north-1", "test-model", client); }

    @Test void realSdkSignsConverseAndSendsSharedSchemaAndBoundedOutput() throws Exception {
        assertEquals(new Selection(List.of("declared.age"), List.of("confirm_declared")), model().select(context()));
        assertTrue(authorization.get().startsWith("AWS4-HMAC-SHA256"));
        assertTrue(authorization.get().contains("eu-north-1/bedrock/aws4_request"));
        var json = mapper.readTree(request.get());
        assertEquals(500, json.path("inferenceConfig").path("maxTokens").asInt());
        assertEquals("json_schema", json.path("outputConfig").path("textFormat").path("type").asText());
        var schema = mapper.readTree(json.path("outputConfig").path("textFormat")
                .path("structure").path("jsonSchema").path("schema").asText());
        assertFalse(schema.path("additionalProperties").asBoolean());
        assertFalse(schema.path("properties").path("summarySourceIds").has("maxItems"));
        assertEquals("declared.age", schema.path("properties").path("summarySourceIds")
                .path("items").path("enum").get(0).asText());
        assertFalse(request.get().contains("test-secret"));
        assertEquals(1, calls.get());
    }

    @Test void noAutomaticRetryOrRawErrorForThrottling() {
        status = 429;
        response = "{\"message\":\"private-account-details\"}";
        var failure = assertThrows(ResponseStatusException.class, () -> model().select(context()));
        assertTrue(failure.getReason().contains("Rate limit"));
        assertFalse(failure.getReason().contains("private-account-details"));
        assertEquals(1, calls.get());
    }

    @Test void incompleteNonTextOversizedAndUnknownFieldsAreRejected() {
        var mocked = mock(BedrockRuntimeClient.class);
        var model = new BedrockBriefingModel(protocol, "eu-north-1", "test-model", mocked);
        for (var content : List.of(ContentBlock.fromText("{}"),
                ContentBlock.fromText(" ".repeat(65537)), ContentBlock.fromText("{\"advice\":\"buy\"}"))) {
            when(mocked.converse(any(ConverseRequest.class))).thenReturn(ConverseResponse.builder().stopReason(StopReason.END_TURN)
                    .output(ConverseOutput.fromMessage(Message.builder().role(ConversationRole.ASSISTANT).content(content).build())).build());
            if ("{}".equals(content.text())) {
                // Required ID shape is checked again by BriefingService.
                assertEquals(new Selection(null, null), model.select(context()));
            } else assertThrows(ResponseStatusException.class, () -> model.select(context()));
        }
        when(mocked.converse(any(ConverseRequest.class))).thenReturn(ConverseResponse.builder().stopReason(StopReason.MAX_TOKENS).build());
        assertThrows(ResponseStatusException.class, () -> model.select(context()));
        when(mocked.converse(any(ConverseRequest.class))).thenReturn(ConverseResponse.builder().stopReason(StopReason.END_TURN)
                .output(ConverseOutput.fromMessage(Message.builder().role(ConversationRole.ASSISTANT).content(
                        ContentBlock.fromText("{}"), ContentBlock.fromText("{}")).build())).build());
        assertThrows(ResponseStatusException.class, () -> model.select(context()));
        when(mocked.converse(any(ConverseRequest.class))).thenReturn(ConverseResponse.builder().stopReason(StopReason.END_TURN)
                .output(ConverseOutput.fromMessage(Message.builder().role(ConversationRole.ASSISTANT).content(
                        ContentBlock.fromReasoningContent(ReasoningContentBlock.fromReasoningText(
                                ReasoningTextBlock.builder().text("private reasoning").signature("test").build())),
                        ContentBlock.fromText("{\"summarySourceIds\":[\"declared.age\"],\"questionIds\":[\"confirm_declared\"]}"))
                        .build())).build());
        assertEquals(new Selection(List.of("declared.age"), List.of("confirm_declared")), model.select(context()));
    }

    @Test void credentialFailuresAndAccessDenialsAreExplicitAndSanitized() {
        var mocked = mock(BedrockRuntimeClient.class);
        var model = new BedrockBriefingModel(protocol, "eu-north-1", "test-model", mocked);
        when(mocked.converse(any(ConverseRequest.class))).thenThrow(SdkClientException.create("private-credential-path"));
        var error = assertThrows(ResponseStatusException.class, () -> model.select(context()));
        assertTrue(error.getReason().contains("credential chain"));
        assertFalse(error.getReason().contains("private-credential-path"));
        when(mocked.converse(any(ConverseRequest.class))).thenThrow(BedrockRuntimeException.builder().statusCode(403)
                .message("private-account-id").build());
        assertTrue(assertThrows(ResponseStatusException.class,
                () -> model.select(context())).getReason().contains("bedrock:InvokeModel"));
    }

    @Test void disabledBuildsNoCloudClientAndEnabledRequiresRegionAndModel() {
        var disabled = new BedrockBriefingModel(protocol, false, "", "");
        assertFalse(disabled.enabled());
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> disabled.select(context())).getStatusCode().value());
        disabled.close();
        assertThrows(IllegalArgumentException.class, () -> new BedrockBriefingModel(protocol, true, "", ""));
        assertEquals(0, calls.get());
    }
}
