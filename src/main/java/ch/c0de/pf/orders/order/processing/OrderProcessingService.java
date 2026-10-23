package ch.c0de.pf.orders.order.processing;

import ch.c0de.pf.orders.order.OrderService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Service;

@Service
public class OrderProcessingService {

    private final OrderService orders;
    private final OrderProcessor processor;
    private final ObservationRegistry observations;

    public OrderProcessingService(OrderService orders, OrderProcessor processor, ObservationRegistry observations) {
        this.orders = orders;
        this.processor = processor;
        this.observations = observations;
    }

    // Two short transactions around the work: a crash mid-way leaves PROCESSING, and redelivery resumes it.
    // The observation around the work is one timer (order_processing_seconds) and one span.
    public void process(Long orderId) {
        orders.startProcessing(orderId).ifPresent(order -> {
            Observation.createNotStarted("order.processing", observations)
                .contextualName("order processing")
                .lowCardinalityKeyValue("order.currency", order.getCurrency())
                .observe(() -> processor.process(order));
            orders.completeProcessing(orderId);
        });
    }
}
