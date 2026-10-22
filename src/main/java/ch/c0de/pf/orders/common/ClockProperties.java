package ch.c0de.pf.orders.common;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("orders.clock")
public record ClockProperties(@DefaultValue("PT0S") Duration offset) {
}
