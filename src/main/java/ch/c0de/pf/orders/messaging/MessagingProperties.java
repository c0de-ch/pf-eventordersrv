package ch.c0de.pf.orders.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("orders.messaging")
public record MessagingProperties(@DefaultValue("order.created") String orderCreatedQueue) {
}
