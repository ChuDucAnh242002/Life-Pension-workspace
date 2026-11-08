package fi.adviser.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.jms.core.SessionCallback;
import java.util.UUID;
import static fi.adviser.workspace.PolicyModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MqPolicyAdapterTest {
    private final JmsTemplate jms = mock(JmsTemplate.class);
    private final Session session = mock(Session.class);
    private final MessageConsumer consumer = mock(MessageConsumer.class);
    private final TextMessage message = mock(TextMessage.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private MqPolicyAdapter adapter;
    private final WireRequest request = new WireRequest(UUID.randomUUID(), 1, Fixture.SOLE_TRADER);

    @BeforeEach void setup() throws Exception {
        adapter = new MqPolicyAdapter(jms, mapper, "DEV.POLICY.REQUESTS", "DEV.POLICY.REPLIES");
        when(session.createQueue(anyString())).thenReturn(mock(Queue.class));
        when(session.createConsumer(any(Destination.class))).thenReturn(consumer);
        when(consumer.receiveNoWait()).thenReturn(message);
        when(message.getText()).thenReturn(mapper.writeValueAsString(request));
        when(message.getJMSCorrelationID()).thenReturn(request.requestId().toString());
        doAnswer(invocation -> {
            SessionCallback<?> callback = invocation.getArgument(0);
            return callback.doInJms(session);
        }).when(jms).execute(org.mockito.ArgumentMatchers.<SessionCallback<Boolean>>any(), eq(true));
    }

    @Test void sendsJsonWithApplicationCorrelationAndSchema() throws Exception {
        when(session.createTextMessage(anyString())).thenReturn(message);
        doAnswer(invocation -> {
            MessageCreator creator = invocation.getArgument(1);
            creator.createMessage(session);
            return null;
        }).when(jms).send(eq("DEV.POLICY.REQUESTS"), any(MessageCreator.class));
        adapter.send(request);
        verify(session).createTextMessage(mapper.writeValueAsString(request));
        verify(message).setJMSCorrelationID(request.requestId().toString());
        verify(message).setIntProperty("policySchemaVersion", 1);
    }

    @Test void commitsMessageOnlyAfterHandlerSucceeds() throws Exception {
        assertTrue(adapter.consumeRequest(value -> {
            assertEquals(request, value);
            try { verify(session, never()).commit(); }
            catch (JMSException failure) { throw new IllegalStateException(failure); }
        }));
        verify(session).commit();
        verify(session, never()).rollback();
    }

    @Test void rollsBackWhenHandlerOrDatabaseFails() throws Exception {
        assertThrows(IllegalStateException.class, () -> adapter.consumeRequest(value -> {
            throw new IllegalStateException("Database unavailable");
        }));
        verify(session).rollback();
        verify(session, never()).commit();
    }

    @Test void mismatchedJmsCorrelationIsRetainedAndNotDelivered() throws Exception {
        when(message.getJMSCorrelationID()).thenReturn(UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class, () -> adapter.consumeRequest(value -> fail("Invalid message must not reach handler")));
        verify(session).rollback();
        verify(session, never()).commit();
    }

    @Test void malformedJsonIsRetainedAndNotDelivered() throws Exception {
        when(message.getText()).thenReturn("{not-json");
        assertThrows(IllegalArgumentException.class, () -> adapter.consumeRequest(value -> fail("Invalid message must not reach handler")));
        verify(session).rollback();
    }

    @Test void emptyQueueIsNotAnError() throws Exception {
        when(consumer.receiveNoWait()).thenReturn(null);
        assertFalse(adapter.consumeRequest(value -> fail("Empty queue")));
        verify(session).commit();
    }
}
