package ch.c0de.pf.orders.order;

import java.math.BigDecimal;

public record CreateOrder(String customerId, BigDecimal amount, String currency) {
}
