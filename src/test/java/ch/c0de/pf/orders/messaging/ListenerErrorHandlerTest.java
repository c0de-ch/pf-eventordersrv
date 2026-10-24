package ch.c0de.pf.orders.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.c0de.pf.orders.order.processing.OrderProcessingException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.jakarta9.instrument.jms.JmsProcessObservationContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.jms.listener.adapter.ListenerExecutionFailedException;

class ListenerErrorHandlerTest {

    private final ObservationRegistry observations = ObservationRegistry.create();
    private final ListenerErrorHandler handler = new ListenerErrorHandler(observations);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ListenerErrorHandler.class);

    @BeforeEach
    void captureLog() {
        observations.observationConfig().observationHandler(context -> true);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(appender);
    }

    @Test
    void expectedFailureIsOneLineWithOrderIdDeliveryAndCause() throws JMSException {
        Message message = mock(Message.class);
        when(message.getJMSCorrelationID()).thenReturn("42");
        when(message.propertyExists("JMSXDeliveryCount")).thenReturn(true);
        when(message.getIntProperty("JMSXDeliveryCount")).thenReturn(3);
        OrderProcessingException cause = new OrderProcessingException("simulated permanent failure for customer FAIL-1");

        whileProcessing(message, () -> handler.handleError(new ListenerExecutionFailedException("Listener method threw exception", cause)));

        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(keyValues(event))
            .containsEntry("order.id", "42")
            .containsEntry("delivery", 3)
            .containsEntry("error.type", OrderProcessingException.class.getName())
            .containsEntry("error.message", "simulated permanent failure for customer FAIL-1");
    }

    @Test
    void unexpectedFailureOutsideAnObservationKeepsTheStackTrace() {
        handler.handleError(new ListenerExecutionFailedException("Listener method threw exception", new NullPointerException("boom")));

        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(keyValues(event)).doesNotContainKeys("order.id", "delivery", "error.type", "error.message");
    }

    // The container keeps the JMS observation open while it calls the error handler
    private void whileProcessing(Message message, Runnable action) {
        Observation observation = Observation.createNotStarted("jms.message.process", () -> new JmsProcessObservationContext(message), observations)
            .start();
        try (Observation.Scope scope = observation.openScope()) {
            action.run();
        } finally {
            observation.stop();
        }
    }

    // Logback returns null, not an empty list, for a line without key-values
    private static Map<String, Object> keyValues(ILoggingEvent event) {
        List<KeyValuePair> pairs = Objects.requireNonNullElse(event.getKeyValuePairs(), List.of());
        return pairs.stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }
}
