package fi.adviser.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.function.Consumer;
import static fi.adviser.workspace.PolicyModels.*;

@Component
@ConditionalOnProperty(name = "app.policy.transport", havingValue = "mq")
public class MqPolicyAdapter implements PolicyPort {
    private final JmsTemplate jms;
    private final ObjectMapper mapper;
    private final String requestQueue;
    private final String replyQueue;

    public MqPolicyAdapter(JmsTemplate jms, ObjectMapper mapper,
                           @Value("${app.policy.mq.request-queue}") String requestQueue,
                           @Value("${app.policy.mq.reply-queue}") String replyQueue) {
        this.jms = jms;
        this.mapper = mapper;
        this.requestQueue = requestQueue;
        this.replyQueue = replyQueue;
    }

    public String transport() { return "IBM_MQ"; }
    public void send(WireRequest request) { send(requestQueue, request.requestId(), request); }
    public void reply(WireReply reply) { send(replyQueue, reply.requestId(), reply); }
    public boolean consumeRequest(Consumer<WireRequest> handler) {
        return consume(requestQueue, WireRequest.class, handler);
    }
    public boolean consumeReply(Consumer<WireReply> handler) {
        return consume(replyQueue, WireReply.class, handler);
    }

    private void send(String queue, UUID id, Object value) {
        final String json;
        try { json = mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot encode policy message.", failure); }
        jms.send(queue, session -> {
            var message = session.createTextMessage(json);
            message.setJMSCorrelationID(id.toString());
            message.setIntProperty("policySchemaVersion", 1);
            return message;
        });
    }

    private <T> boolean consume(String queue, Class<T> type, Consumer<T> handler) {
        Boolean consumed = jms.execute(session -> {
            try (var consumer = session.createConsumer(session.createQueue(queue))) {
                var message = consumer.receiveNoWait();
                if (message == null) { session.commit(); return false; }
                if (!(message instanceof TextMessage text) || text.getText().length() > 8192) {
                    throw new IllegalArgumentException("Policy message must be bounded JSON text.");
                }
                T value;
                try { value = mapper.readValue(text.getText(), type); }
                catch (JsonProcessingException failure) { throw new IllegalArgumentException("Invalid policy message JSON.", failure); }
                UUID id = value instanceof WireRequest request ? request.requestId() : ((WireReply) value).requestId();
                if (id == null || !id.toString().equals(message.getJMSCorrelationID())) {
                    throw new IllegalArgumentException("Policy JMS correlation does not match its payload.");
                }
                handler.accept(value);
                // Database commit (or simulator reply send) completes before acknowledging this message.
                session.commit();
                return true;
            } catch (JMSException | RuntimeException failure) {
                try { session.rollback(); }
                catch (JMSException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }, true);
        return Boolean.TRUE.equals(consumed);
    }
}
