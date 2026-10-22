package ch.c0de.pf.orders.order;

public class IllegalStatusTransitionException extends IllegalStateException {

    public IllegalStatusTransitionException(Long orderId, OrderStatus from, OrderStatus to) {
        super("order %s: illegal status transition %s -> %s".formatted(orderId, from, to));
    }
}
