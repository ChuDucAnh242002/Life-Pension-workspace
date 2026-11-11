package fi.adviser.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PolicyWorker {
    private static final Logger log = LoggerFactory.getLogger(PolicyWorker.class);
    private final PolicyService service;
    private final PolicyPort port;
    private final PolicySimulator simulator;
    private final boolean simulate;

    public PolicyWorker(PolicyService service, PolicyPort port, PolicySimulator simulator,
                        @Value("${app.policy.simulator:false}") boolean simulate) {
        this.service = service;
        this.port = port;
        this.simulator = simulator;
        this.simulate = simulate;
    }

    @Scheduled(fixedDelayString = "${app.policy.poll-ms:3000}", initialDelayString = "${app.policy.poll-ms:3000}")
    public void poll() {
        service.expire();
        for (var request : service.pending()) {
            try {
                port.send(request);
                service.published(request.requestId());
            } catch (RuntimeException failure) {
                service.publicationFailed(request.requestId());
                log.warn("Policy publication failed; retry retained. Exception type: {}", failure.getClass().getSimpleName());
            }
        }
        try {
            if (simulate) {
                for (int i = 0; i < 20 && port.consumeRequest(request -> port.reply(simulator.respond(request))); i++) {}
            }
            for (int i = 0; i < 20 && port.consumeReply(service::accept); i++) {}
        } catch (RuntimeException failure) {
            // Raw MQ/JMS messages may include credentials or payloads.
            log.warn("Policy consumption failed; transport retains message for retry. Exception type: {}",
                    failure.getClass().getSimpleName());
        }
    }
}
