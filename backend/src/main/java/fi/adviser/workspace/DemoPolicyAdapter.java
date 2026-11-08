package fi.adviser.workspace;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import static fi.adviser.workspace.PolicyModels.*;

@Component
@ConditionalOnProperty(name = "app.policy.transport", havingValue = "demo")
public class DemoPolicyAdapter implements PolicyPort {
    private final Queue<WireRequest> requests = new ConcurrentLinkedQueue<>();
    private final Queue<WireReply> replies = new ConcurrentLinkedQueue<>();

    public String transport() { return "IN_PROCESS_DEMO"; }
    public void send(WireRequest request) { requests.add(request); }
    public void reply(WireReply reply) { replies.add(reply); }
    public boolean consumeRequest(Consumer<WireRequest> handler) { return consume(requests, handler); }
    public boolean consumeReply(Consumer<WireReply> handler) { return consume(replies, handler); }

    private <T> boolean consume(Queue<T> queue, Consumer<T> handler) {
        T message = queue.poll();
        if (message == null) return false;
        try {
            handler.accept(message);
            return true;
        } catch (RuntimeException failure) {
            queue.add(message);
            throw failure;
        }
    }
}
