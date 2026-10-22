package ch.c0de.pf.orders.order.processing;

import ch.c0de.pf.orders.order.Order;

public interface OrderProcessor {

    void process(Order order);
}
