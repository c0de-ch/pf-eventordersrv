package ch.c0de.pf.orders.messaging;

import ch.c0de.pf.orders.order.OrderAcceptedEvent;
import ch.c0de.pf.orders.order.processing.OrderProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "orders.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderAcceptedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderAcceptedListener.class);

    private final OrderProcessingService processing;

    public OrderAcceptedListener(OrderProcessingService processing) {
        this.processing = processing;
    }

    // Any exception rolls the session back; the broker redelivers and finally dead-letters the message
    @JmsListener(destination = "${orders.messaging.order-accepted-queue}")
    public void onOrderAccepted(OrderAcceptedEvent event,
                               @Header(JmsOutboxPublisher.EVENT_ID_PROPERTY) long eventId,
                               @Header(name = "JMSXDeliveryCount", required = false) Integer deliveryCount) {
        try (MDC.MDCCloseable orderId = MDC.putCloseable("order.id", String.valueOf(event.orderId()));
             MDC.MDCCloseable id = MDC.putCloseable("event.id", String.valueOf(eventId))) {
            log.atInfo().addKeyValue("event.action", "order.event.received").addKeyValue("delivery", deliveryCount)
                .log("order.accepted received");
            processing.process(event.orderId());
        }
    }
}
