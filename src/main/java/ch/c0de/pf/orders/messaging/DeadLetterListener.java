package ch.c0de.pf.orders.messaging;

import ch.c0de.pf.orders.order.OrderMetrics;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "orders.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

    private final DeadLetterService deadLetters;
    private final OrderMetrics metrics;

    public DeadLetterListener(DeadLetterService deadLetters, OrderMetrics metrics) {
        this.deadLetters = deadLetters;
        this.metrics = metrics;
    }

    // Raw message on purpose: a poison body must not fail again here; every dead letter is stored, usable or not
    @JmsListener(destination = "${orders.messaging.order-accepted-dlq}")
    public void onDeadLetter(Message message) throws JMSException {
        String correlationId = message.getJMSCorrelationID();
        try (MDC.MDCCloseable scope = MDC.putCloseable("order.id", correlationId == null ? "unknown" : correlationId)) {
            DeadLetter stored = deadLetters.handle(message);
            metrics.deadLettered();
            boolean usable = stored.getOrderId() != null;
            LoggingEventBuilder line = (usable ? log.atWarn() : log.atError())
                .addKeyValue("event.action", "order.event.dead_lettered")
                .addKeyValue("dead_letter.id", stored.getId())
                .addKeyValue("origin", stored.getOriginQueue() == null ? "unknown" : stored.getOriginQueue());
            if (!usable) {
                line = line.addKeyValue("reason", "no_usable_order_id").addKeyValue("message.id", stored.getMessageId());
            }
            line.log(usable ? "order.accepted dead-lettered" : "dead letter stored, order id unusable");
        }
    }
}
