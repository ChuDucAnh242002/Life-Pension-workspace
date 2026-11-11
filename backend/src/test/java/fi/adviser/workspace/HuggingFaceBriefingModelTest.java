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

class HuggingFaceBriefingModelTest {
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private int status = 200;
    private String response;

    @BeforeEach void start() throws Exception {
        response = envelope("{\"summarySourceIds\":[\"declared.age\"],\"questionIds\":[\"confirm_declared\"]}", "stop");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private String envelope(String content, String finish) throws Exception {
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", finish,
                "message", Map.of("content", content)))));
    }

    private HuggingFaceBriefingModel client() {
        return new HuggingFaceBriefingModel(mapper, true, "test-provider", "test-model", "test-token",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"));
    }

    private Context context() {
        return new Context(1, "NONE", List.of(new Source("declared.age", "Age", "38", "Declared")),
                List.of(new Question("confirm_declared", "Confirm facts.", "declared.age")));
    }

    @Test void sendsExplicitModelStrictEnumSchemaAndServerSideAuthentication() throws Exception {
        assertEquals(new Selection(List.of("declared.age"), List.of("confirm_declared")), client().select(context()));
        assertEquals("Bearer test-token", authorization.get());
        var payload = mapper.readTree(requestBody.get());
        assertEquals("test-model:test-provider", payload.path("model").asText());
        assertEquals(500, payload.path("max_tokens").asInt());
        var schema = payload.path("response_format").path("json_schema");
        assertTrue(schema.path("strict").asBoolean());
        assertFalse(schema.path("schema").path("additionalProperties").asBoolean());
        assertEquals("declared.age", schema.path("schema").path("properties")
                .path("summarySourceIds").path("items").path("enum").get(0).asText());
        assertFalse(requestBody.get().contains("test-token"));
    }

    @Test void disabledMeansNoHttpRequestAndExplicit503() throws Exception {
        var disabled = new HuggingFaceBriefingModel(mapper, false, "", "", "");
        assertFalse(disabled.enabled());
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> disabled.select(context())).getStatusCode().value());
        assertNull(requestBody.get());
    }

    @Test void rejectsTruncatedMalformedRefusedAndUnknownFieldResponses() throws Exception {
        for (String body : List.of("not JSON", "null", "{\"choices\":[]}",
                envelope("{}", "length"), envelope("{\"summarySourceIds\":[],\"questionIds\":[],\"advice\":\"buy\"}", "stop"))) {
            response = body;
            assertEquals(502, assertThrows(ResponseStatusException.class,
                    () -> client().select(context())).getStatusCode().value());
        }
    }

    @Test void providerErrorDoesNotExposeRawBodyOrRetry() {
        status = 429;
        response = "private provider error";
        var failure = assertThrows(ResponseStatusException.class, () -> client().select(context()));
        assertEquals(502, failure.getStatusCode().value());
        assertFalse(failure.getReason().contains(response));
    }

    @Test void reportsBillingAuthorizationRequestAndRateLimitFailuresWithoutProviderBody() {
        response = "private provider error";
        for (var entry : Map.of(402, "inference credits", 401, "authorization failed",
                403, "authorization failed", 400, "rejected the inference request",
                404, "rejected the inference request", 422, "rejected the inference request",
                429, "rate limit", 503, "inference failed").entrySet()) {
            status = entry.getKey();
            var failure = assertThrows(ResponseStatusException.class, () -> client().select(context()));
            assertEquals(502, failure.getStatusCode().value());
            assertTrue(failure.getReason().contains(entry.getValue()));
            assertTrue(failure.getReason().contains("HTTP " + status));
            assertFalse(failure.getReason().contains(response));
        }
    }

    @Test void publicConfigurationUsesUnifiedRouterAndRejectsProviderOverrideInModel() {
        assertEquals(URI.create("https://router.huggingface.co/v1/chat/completions"),
                HuggingFaceBriefingModel.endpoint(true, "deepinfra"));
        assertThrows(IllegalArgumentException.class,
                () -> new HuggingFaceBriefingModel(mapper, true, "test-provider", "test-model:auto",
                        "test-token", URI.create("http://localhost/")));
    }

    @Test void rejectsOversizedResponseAndDisconnectedEndpoint() {
        response = " ".repeat(65537);
        assertThrows(ResponseStatusException.class, () -> client().select(context()));
        server.stop(0);
        assertThrows(ResponseStatusException.class, () -> client().select(context()));
    }

    @Test void enabledConfigurationRequiresExplicitProviderModelAndPrivateToken() {
        assertThrows(IllegalArgumentException.class,
                () -> new HuggingFaceBriefingModel(mapper, true, "../other", "model", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new HuggingFaceBriefingModel(mapper, true, "cerebras", "", ""));
    }
}
