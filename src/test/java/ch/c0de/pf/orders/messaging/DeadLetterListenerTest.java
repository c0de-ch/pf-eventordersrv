package ch.c0de.pf.orders.messaging;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.c0de.pf.orders.order.OrderMetrics;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeadLetterListenerTest {

    @Mock
    DeadLetterService deadLetters;
    @Mock
    OrderMetrics metrics;
    @Mock
    Message message;
    @InjectMocks
    DeadLetterListener listener;

    @Test
    void storesTheDeadLetterAndCountsIt() throws JMSException {
        when(message.getJMSCorrelationID()).thenReturn("42");
        when(deadLetters.handle(message)).thenReturn(deadLetter(42L));

        listener.onDeadLetter(message);

        verify(deadLetters).handle(message);
        verify(metrics).deadLettered();
    }

    @Test
    void keepsDeadLettersWithoutUsableOrderId() throws JMSException {
        when(message.getJMSCorrelationID()).thenReturn(null);
        when(deadLetters.handle(message)).thenReturn(deadLetter(null));

        listener.onDeadLetter(message);

        verify(deadLetters).handle(message);
        verify(metrics).deadLettered();
    }

    private static DeadLetter deadLetter(Long orderId) {
        return new DeadLetter(orderId, 7L, "OrderAccepted", "ID:1", null, "order.accepted",
            orderId == null ? null : String.valueOf(orderId), null, null, "{}", "{}");
    }
}
