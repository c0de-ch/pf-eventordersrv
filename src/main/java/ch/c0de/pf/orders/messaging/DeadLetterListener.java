package ch.c0de.pf.orders.messaging;

import ch.c0de.pf.orders.order.OrderService;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "orders.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

    private final OrderService orders;

    public DeadLetterListener(OrderService orders) {
        this.orders = orders;
    }

    // Raw message on purpose: a poison body must not fail again here, the order id travels in the correlation id
    @JmsListener(destination = "${orders.messaging.order-created-dlq}")
    public void onDeadLetter(Message message) throws JMSException {
        String correlationId = message.getJMSCorrelationID();
        String origin = message.propertyExists("_AMQ_ORIG_QUEUE") ? message.getStringProperty("_AMQ_ORIG_QUEUE") : "unknown";
        if (correlationId == null) {
            log.error("dead letter without order id messageId={} origin={}", message.getJMSMessageID(), origin);
            return;
        }
        try {
            long orderId = Long.parseLong(correlationId);
            log.warn("order.created dead-lettered orderId={} origin={}", orderId, origin);
            orders.failProcessing(orderId, "dead-lettered after repeated processing failures");
        } catch (NumberFormatException e) {
            log.error("dead letter with unusable order id correlationId={} messageId={}", correlationId, message.getJMSMessageID());
        }
    }
}
