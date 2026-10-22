package ch.c0de.pf.orders.order.api;

import ch.c0de.pf.orders.order.Order;
import ch.c0de.pf.orders.order.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record OrderSummary(
    Long id,
    String customerId,
    BigDecimal amount,
    String currency,
    OrderStatus status,
    Instant createdAt,
    Instant updatedAt) {

    static OrderSummary from(Order order) {
        return new OrderSummary(order.getId(), order.getCustomerId(), order.getAmount(), order.getCurrency(),
            order.getStatus(), order.getCreatedAt(), order.getUpdatedAt());
    }
}
