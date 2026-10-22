package ch.c0de.pf.orders.common;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    private static final Logger log = LoggerFactory.getLogger(ClockConfig.class);

    // Single source of business time; the offset shifts it for test scenarios
    @Bean
    Clock clock(ClockProperties properties) {
        Clock system = Clock.systemUTC();
        if (properties.offset().isZero()) {
            return system;
        }
        Clock shifted = Clock.offset(system, properties.offset());
        log.warn("application clock shifted offset={} now={}", properties.offset(), Instant.now(shifted));
        return shifted;
    }
}
