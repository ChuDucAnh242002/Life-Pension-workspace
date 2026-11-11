package fi.adviser.workspace;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static fi.adviser.workspace.BriefingModels.*;
import static org.junit.jupiter.api.Assertions.*;

class AzureBriefingModelTest {
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private HttpServer server;
    private int status = 200;
    private String body;
    private final AtomicReference<String> request = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> bearer = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        body = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                "message", Map.of("content", "{\"summarySourceIds\":[\"declared.age\"],\"questionIds\":[\"confirm_declared\"]}")))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
            bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    private AzureBriefingModel client() {
        return new AzureBriefingModel(mapper, true, "my-small-deployment", "private-test-key",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1/chat/completions"));
    }
    private Context context() {
        return new Context(1, "NONE", List.of(new Source("declared.age", "Age", "38", "Declared")),
                List.of(new Question("confirm_declared", "Confirm facts.", "declared.age")));
    }

    @Test void authenticatesWithApiKeyAndUsesDeploymentNameWithoutHfSuffix() throws Exception {
        assertEquals(new Selection(List.of("declared.age"), List.of("confirm_declared")), client().select(context()));
        assertEquals("private-test-key", apiKey.get());
        assertNull(bearer.get());
        var payload = mapper.readTree(request.get());
        assertEquals("my-small-deployment", payload.path("model").asText());
        assertEquals(500, payload.path("max_completion_tokens").asInt());
        assertTrue(payload.path("response_format").path("json_schema").path("strict").asBoolean());
        assertFalse(payload.path("response_format").path("json_schema").path("schema")
                .path("properties").path("summarySourceIds").has("maxItems"));
        assertFalse(request.get().contains("private-test-key"));
        assertEquals("Azure OpenAI in Foundry", client().provider());
    }

    @Test void resourceRootUsesV1AndRejectsUnsafeOrWrongEndpointTypes() {
        assertEquals(URI.create("https://test.openai.azure.com/openai/v1/chat/completions"),
                AzureBriefingModel.endpoint(true, "https://test.openai.azure.com/"));
        assertEquals(URI.create("https://test.services.ai.azure.com/openai/v1/chat/completions"),
                AzureBriefingModel.endpoint(true, "https://test.services.ai.azure.com"));
        for (String endpoint : List.of("http://test.openai.azure.com", "https://example.com",
                "https://test.openai.azure.com/other", "https://test.openai.azure.com?x=1",
                "https://key@test.openai.azure.com", "https://test.openai.azure.com:8443")) {
            assertThrows(IllegalArgumentException.class, () -> AzureBriefingModel.endpoint(true, endpoint));
        }
    }

    @Test void disabledDoesNotReadMissingKeyOrMakeHttpCall() throws Exception {
        var disabled = new AzureBriefingModel(mapper, false, "", "", "/missing/secret");
        assertFalse(disabled.enabled());
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> disabled.select(context())).getStatusCode().value());
        assertNull(request.get());
    }

    @Test void rejectsIncompleteUnknownFieldAndMalformedResponse() {
        for (String response : List.of("null", "not JSON", "{\"choices\":[]}",
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"extra\\\":1}\"}}]}")) {
            body = response;
            assertEquals(502, assertThrows(ResponseStatusException.class,
                    () -> client().select(context())).getStatusCode().value());
        }
    }

    @Test void errorIsActionableAndNeverExposesProviderBody() {
        status = 403;
        body = "private-account-details";
        var failure = assertThrows(ResponseStatusException.class, () -> client().select(context()));
        assertTrue(failure.getReason().contains("Azure OpenAI"));
        assertTrue(failure.getReason().contains("authorization failed"));
        assertFalse(failure.getReason().contains(body));
    }
}
