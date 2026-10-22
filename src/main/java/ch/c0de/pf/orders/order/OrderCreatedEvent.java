package ch.c0de.pf.orders.order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderCreatedEvent(Long orderId, String customerId, BigDecimal amount, String currency, Instant occurredAt) {

    public static final String TYPE = "OrderCreated";

    public static OrderCreatedEvent of(Order order, Instant occurredAt) {
        return new OrderCreatedEvent(order.getId(), order.getCustomerId(), order.getAmount(), order.getCurrency(), occurredAt);
    }
}
