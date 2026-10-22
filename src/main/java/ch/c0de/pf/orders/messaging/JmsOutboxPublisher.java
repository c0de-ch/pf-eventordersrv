package ch.c0de.pf.orders.messaging;

import ch.c0de.pf.orders.order.OrderCreatedEvent;
import ch.c0de.pf.orders.outbox.OutboxEvent;
import ch.c0de.pf.orders.outbox.OutboxPublisher;
import jakarta.jms.TextMessage;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

@Component
public class JmsOutboxPublisher implements OutboxPublisher {

    static final String TYPE_PROPERTY = "_type";
    static final String EVENT_ID_PROPERTY = "eventId";

    private final JmsTemplate jms;
    private final MessagingProperties properties;

    public JmsOutboxPublisher(JmsTemplate jms, MessagingProperties properties) {
        this.jms = jms;
        this.properties = properties;
    }

    @Override
    public void publish(OutboxEvent event) {
        jms.send(destinationFor(event.getEventType()), session -> {
            TextMessage message = session.createTextMessage(event.getPayload());
            message.setStringProperty(TYPE_PROPERTY, event.getEventType());
            message.setLongProperty(EVENT_ID_PROPERTY, event.getId());
            message.setJMSCorrelationID(String.valueOf(event.getAggregateId()));
            return message;
        });
    }

    private String destinationFor(String eventType) {
        return switch (eventType) {
            case OrderCreatedEvent.TYPE -> properties.orderCreatedQueue();
            default -> throw new IllegalArgumentException("no destination for event type " + eventType);
        };
    }
}
