package ch.c0de.pf.orders.order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderAcceptedEvent(Long orderId, String customerId, BigDecimal amount, String currency, Instant occurredAt) {

    public static final String TYPE = "OrderAccepted";

    public static OrderAcceptedEvent of(Order order, Instant occurredAt) {
        return new OrderAcceptedEvent(order.getId(), order.getCustomerId(), order.getAmount(), order.getCurrency(), occurredAt);
    }
}
