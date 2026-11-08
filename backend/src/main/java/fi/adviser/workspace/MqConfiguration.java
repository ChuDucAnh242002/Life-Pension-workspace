package fi.adviser.workspace;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.JMSException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
@ConditionalOnProperty(name = "app.policy.transport", havingValue = "mq")
public class MqConfiguration {
    @Bean
    CachingConnectionFactory policyConnectionFactory(
            @Value("${app.policy.mq.host}") String host,
            @Value("${app.policy.mq.port}") int port,
            @Value("${app.policy.mq.queue-manager}") String manager,
            @Value("${app.policy.mq.channel}") String channel,
            @Value("${app.policy.mq.user}") String user,
            @Value("${app.policy.mq.password}") String password,
            @Value("${app.policy.mq.password-file:}") String passwordFile) throws JMSException, IOException {
        var factory = new MQConnectionFactory();
        factory.setHostName(host);
        factory.setPort(port);
        factory.setQueueManager(manager);
        factory.setChannel(channel);
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        factory.setStringProperty(WMQConstants.USERID, user);
        String secret = passwordFile.isBlank() ? password : Files.readString(Path.of(passwordFile)).stripTrailing();
        if (secret.isBlank()) throw new IllegalArgumentException("Configure the MQ app-user password or secret file.");
        factory.setStringProperty(WMQConstants.PASSWORD, secret);
        var cached = new CachingConnectionFactory(factory);
        cached.setSessionCacheSize(2);
        cached.setCacheConsumers(false);
        return cached;
    }

    @Bean
    JmsTemplate policyJms(CachingConnectionFactory policyConnectionFactory) {
        var template = new JmsTemplate(policyConnectionFactory);
        template.setSessionTransacted(true);
        template.setDeliveryPersistent(true);
        return template;
    }
}
