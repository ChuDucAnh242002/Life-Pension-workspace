package fi.adviser.workspace;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;

@Configuration
@EnableScheduling
public class PolicyConfiguration {
    @Bean Clock policyClock() { return Clock.systemUTC(); }
}
