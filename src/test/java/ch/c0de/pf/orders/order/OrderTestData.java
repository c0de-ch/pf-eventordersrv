package ch.c0de.pf.orders.order;

import java.math.BigDecimal;
import org.springframework.test.util.ReflectionTestUtils;

public final class OrderTestData {

    public static final String CUSTOMER_ID = "12345";
    public static final BigDecimal AMOUNT = new BigDecimal("99.99");

    private OrderTestData() {
    }

    public static Order newOrder() {
        return newOrder(CUSTOMER_ID);
    }

    public static Order newOrder(String customerId) {
        return Order.create(customerId, AMOUNT, "CHF");
    }

    public static Order persistedOrder(Long id) {
        return persistedOrder(id, CUSTOMER_ID);
    }

    public static Order persistedOrder(Long id, String customerId) {
        Order order = newOrder(customerId);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}
