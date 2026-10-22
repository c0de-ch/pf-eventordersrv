package ch.c0de.pf.orders.order.api;

import ch.c0de.pf.orders.order.Order;
import ch.c0de.pf.orders.order.OrderStatus;
import ch.c0de.pf.orders.order.OrderStatusHistory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
    Long id,
    String customerId,
    BigDecimal amount,
    String currency,
    OrderStatus status,
    Instant createdAt,
    Instant updatedAt,
    List<StatusChange> history) {

    public record StatusChange(OrderStatus from, OrderStatus to, String reason, Instant at) {

        static StatusChange from(OrderStatusHistory entry) {
            return new StatusChange(entry.getFromStatus(), entry.getToStatus(), entry.getReason(), entry.getCreatedAt());
        }
    }

    static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getCustomerId(), order.getAmount(), order.getCurrency(),
            order.getStatus(), order.getCreatedAt(), order.getUpdatedAt(),
            order.getHistory().stream().map(StatusChange::from).toList());
    }
}
