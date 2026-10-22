package ch.c0de.pf.orders.order.processing;

import ch.c0de.pf.orders.order.OrderService;
import org.springframework.stereotype.Service;

@Service
public class OrderProcessingService {

    private final OrderService orders;
    private final OrderProcessor processor;

    public OrderProcessingService(OrderService orders, OrderProcessor processor) {
        this.orders = orders;
        this.processor = processor;
    }

    // Two short transactions around the work: a crash mid-way leaves PROCESSING, and redelivery resumes it
    public void process(Long orderId) {
        orders.startProcessing(orderId).ifPresent(order -> {
            processor.process(order);
            orders.completeProcessing(orderId);
        });
    }
}
