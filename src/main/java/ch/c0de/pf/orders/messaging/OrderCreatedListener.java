package ch.c0de.pf.orders.messaging;

import ch.c0de.pf.orders.order.OrderCreatedEvent;
import ch.c0de.pf.orders.order.processing.OrderProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "orders.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedListener.class);

    private final OrderProcessingService processing;

    public OrderCreatedListener(OrderProcessingService processing) {
        this.processing = processing;
    }

    // Any exception rolls the session back; the broker redelivers and finally dead-letters the message
    @JmsListener(destination = "${orders.messaging.order-created-queue}")
    public void onOrderCreated(OrderCreatedEvent event,
                               @Header(JmsOutboxPublisher.EVENT_ID_PROPERTY) long eventId,
                               @Header(name = "JMSXDeliveryCount", required = false) Integer deliveryCount) {
        log.info("order.created received orderId={} eventId={} delivery={}", event.orderId(), eventId, deliveryCount);
        processing.process(event.orderId());
    }
}
