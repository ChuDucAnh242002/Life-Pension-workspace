package fi.adviser.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import static fi.adviser.workspace.BriefingModels.*;

final class BriefingHttp {
    private static final Logger log = LoggerFactory.getLogger(BriefingHttp.class);
    private final ObjectMapper mapper;
    private final BriefingProtocol protocol;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    BriefingHttp(ObjectMapper mapper) { this.mapper = mapper; this.protocol = new BriefingProtocol(mapper); }

    Selection select(Context context, String model, URI endpoint, String header, String secret,
                     String provider, String tokenLimitField) {
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                    .header(header, secret).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(protocol.chatPayload(context, model, tokenLimitField)))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (var stream = response.body()) { body = stream.readNBytes(65537); }
            if (response.statusCode() != 200) {
                log.warn("{} briefing request rejected; HTTP status {}.", provider, response.statusCode());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, rejection(provider, response.statusCode()));
            }
            if (body.length > 65536) throw new IOException("Oversized provider response.");
            return protocol.chatResponse(body);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            log.warn("{} briefing request interrupted.", provider);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI request interrupted. No draft was saved.");
        } catch (IOException failure) {
            log.warn("{} briefing transport/JSON failure: {}.", provider, failure.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AI timed out, was unreachable, or returned invalid JSON. No draft was saved.");
        }
    }

    private String rejection(String provider, int status) {
        String reason = switch (status) {
            case 401, 403 -> "authorization failed. Check credentials and inference permissions.";
            case 402 -> "requires inference credits. Check provider billing and purchase credits.";
            case 400, 404, 422 -> "rejected the inference request. Check model/deployment and structured-output support.";
            case 429 -> "rate limit reached. Wait before trying again.";
            default -> "inference failed. Try again later or contact the operator.";
        };
        return provider + " " + reason + " (HTTP " + status + "). No draft was saved.";
    }
}
