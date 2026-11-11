package fi.adviser.workspace;

import java.util.function.Consumer;
import static fi.adviser.workspace.PolicyModels.*;

public interface PolicyPort {
    String transport();
    void send(WireRequest request);
    boolean consumeRequest(Consumer<WireRequest> handler);
    boolean consumeReply(Consumer<WireReply> handler);
    void reply(WireReply reply);
}
