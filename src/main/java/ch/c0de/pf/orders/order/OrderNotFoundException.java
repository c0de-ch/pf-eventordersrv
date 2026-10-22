package ch.c0de.pf.orders.order;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(Long orderId) {
        super("order %d not found".formatted(orderId));
    }
}
