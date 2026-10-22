package ch.c0de.pf.orders.messaging;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.c0de.pf.orders.order.OrderService;
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
    OrderService orders;

    @Mock
    Message message;

    @InjectMocks
    DeadLetterListener listener;

    @Test
    void marksOrderFailedFromCorrelationId() throws JMSException {
        when(message.getJMSCorrelationID()).thenReturn("42");
        when(message.propertyExists("_AMQ_ORIG_QUEUE")).thenReturn(true);
        when(message.getStringProperty("_AMQ_ORIG_QUEUE")).thenReturn("order.created");

        listener.onDeadLetter(message);

        verify(orders).failProcessing(eq(42L), contains("dead-lettered"));
    }

    @Test
    void dropsMessagesWithoutUsableOrderId() throws JMSException {
        when(message.getJMSCorrelationID()).thenReturn(null, "not-a-number");

        listener.onDeadLetter(message);
        listener.onDeadLetter(message);

        verifyNoInteractions(orders);
    }
}
