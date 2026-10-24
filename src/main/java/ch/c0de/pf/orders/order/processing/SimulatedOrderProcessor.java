package ch.c0de.pf.orders.order.processing;

import ch.c0de.pf.orders.order.Order;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

@Component
public class SimulatedOrderProcessor implements OrderProcessor {

    private static final Logger log = LoggerFactory.getLogger(SimulatedOrderProcessor.class);

    private final ProcessingProperties properties;
    private final Set<Long> flakyOrdersSeen = ConcurrentHashMap.newKeySet();

    public SimulatedOrderProcessor(ProcessingProperties properties) {
        this.properties = properties;
    }

    // Transient failures are retried in-process; anything else goes back to the broker
    @Override
    @Retryable(includes = TransientProcessingException.class, maxRetries = 2, delay = 200)
    public void process(Order order) {
        log.debug("processing order orderId={} customerId={} amount={} {}",
            order.getId(), order.getCustomerId(), order.getAmount(), order.getCurrency());
        pause(properties.delay());
        if (order.getCustomerId().startsWith(properties.failingCustomerPrefix())) {
            throw new OrderProcessingException("simulated permanent failure for customer " + order.getCustomerId());
        }
        if (order.getCustomerId().startsWith(properties.flakyCustomerPrefix()) && flakyOrdersSeen.add(order.getId())) {
            throw new TransientProcessingException("simulated transient failure for customer " + order.getCustomerId());
        }
    }

    private static void pause(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OrderProcessingException("processing interrupted");
        }
    }
}
