package ch.c0de.pf.orders.order.processing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("orders.processing")
public record ProcessingProperties(
    @DefaultValue("PT0.5S") Duration delay,
    @DefaultValue("FAIL-") String failingCustomerPrefix,
    @DefaultValue("FLAKY-") String flakyCustomerPrefix) {
}
