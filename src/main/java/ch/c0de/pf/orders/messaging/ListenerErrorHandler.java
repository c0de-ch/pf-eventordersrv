package ch.c0de.pf.orders.messaging;

import ch.c0de.pf.orders.order.processing.OrderProcessingException;
import io.micrometer.jakarta9.instrument.jms.JmsProcessObservationContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.util.ErrorHandler;

// Runs after the listener's MDC scope has closed but inside the JMS observation, whose carrier is the failed message
final class ListenerErrorHandler implements ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ListenerErrorHandler.class);

    private final ObservationRegistry observations;

    ListenerErrorHandler(ObservationRegistry observations) {
        this.observations = observations;
    }

    @Override
    public void handleError(Throwable error) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(error);
        LoggingEventBuilder line = log.atWarn();
        Message message = failedMessage();
        if (message != null) {
            String orderId = correlationId(message);
            Integer delivery = deliveryCount(message);
            if (orderId != null) {
                line = line.addKeyValue("order.id", orderId);
            }
            if (delivery != null) {
                line = line.addKeyValue("delivery", delivery);
            }
        }
        // Expected failures stay one line; anything else keeps its stack trace, once, here
        if (cause instanceof OrderProcessingException) {
            line = line.addKeyValue("error.type", cause.getClass().getName()).addKeyValue("error.message", cause.getMessage());
        } else {
            line = line.setCause(error);
        }
        line.log("message processing failed, session rolled back");
    }

    private Message failedMessage() {
        Observation current = observations.getCurrentObservation();
        return current != null && current.getContext() instanceof JmsProcessObservationContext context ? context.getCarrier() : null;
    }

    private static String correlationId(Message message) {
        try {
            return message.getJMSCorrelationID();
        } catch (JMSException e) {
            return null;
        }
    }

    private static Integer deliveryCount(Message message) {
        try {
            return message.propertyExists("JMSXDeliveryCount") ? message.getIntProperty("JMSXDeliveryCount") : null;
        } catch (JMSException e) {
            return null;
        }
    }
}
