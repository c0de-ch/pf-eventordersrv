package ch.c0de.pf.orders.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("orders.outbox.relay")
public record OutboxRelayProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("PT1S") Duration pollInterval,
    @DefaultValue("50") int batchSize) {
}
