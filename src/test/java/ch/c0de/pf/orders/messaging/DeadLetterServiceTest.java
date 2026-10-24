package ch.c0de.pf.orders.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import ch.c0de.pf.orders.order.OrderService;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class DeadLetterServiceTest {

    private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @Mock
    DeadLetterRepository repository;
    @Mock
    OrderService orders;
    @Mock
    TextMessage message;

    DeadLetterService service() {
        return new DeadLetterService(repository, orders, JsonMapper.builder().build());
    }

    @Test
    void storesEvidenceAndFailsTheOrderInOneGo() throws JMSException {
        when(message.getJMSMessageID()).thenReturn("ID:dlq-1");
        when(message.getJMSCorrelationID()).thenReturn("42");
        when(message.getJMSTimestamp()).thenReturn(1_700_000_000_000L);
        when(message.getText()).thenReturn("{\"orderId\":42}");
        when(message.getPropertyNames()).thenReturn(Collections.enumeration(
            List.of("eventId", "_type", "_AMQ_ORIG_QUEUE", "_AMQ_ORIG_MESSAGE_ID", "traceparent", "JMSXDeliveryCount")));
        when(message.getObjectProperty("eventId")).thenReturn(7L);
        when(message.getObjectProperty("_type")).thenReturn("OrderAccepted");
        when(message.getObjectProperty("_AMQ_ORIG_QUEUE")).thenReturn("order.accepted");
        when(message.getObjectProperty("_AMQ_ORIG_MESSAGE_ID")).thenReturn(123L);
        when(message.getObjectProperty("traceparent")).thenReturn(TRACEPARENT);
        when(message.getObjectProperty("JMSXDeliveryCount")).thenReturn(1);
        when(repository.findByMessageId("ID:dlq-1")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DeadLetter stored = service().handle(message);

        ArgumentCaptor<DeadLetter> saved = ArgumentCaptor.forClass(DeadLetter.class);
        verify(repository).save(saved.capture());
        assertThat(stored).isSameAs(saved.getValue());
        assertThat(stored.getOrderId()).isEqualTo(42L);
        assertThat(stored.getEventId()).isEqualTo(7L);
        assertThat(stored.getEventType()).isEqualTo("OrderAccepted");
        assertThat(stored.getMessageId()).isEqualTo("ID:dlq-1");
        assertThat(stored.getOriginalMessageId()).isEqualTo("123");
        assertThat(stored.getOriginQueue()).isEqualTo("order.accepted");
        assertThat(stored.getCorrelationId()).isEqualTo("42");
        assertThat(stored.getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(stored.getSentAt()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(stored.getBody()).isEqualTo("{\"orderId\":42}");
        assertThat(stored.getProperties()).contains("\"JMSXDeliveryCount\":\"1\"", "\"traceparent\":\"" + TRACEPARENT + "\"");
        verify(orders).failProcessing(42L, DeadLetterService.FAILURE_REASON);
    }

    @Test
    void recognisesARedeliveredDeadLetterByMessageId() throws JMSException {
        DeadLetter existing = new DeadLetter(42L, 7L, "OrderAccepted", "ID:dlq-1", null, "order.accepted", "42", null, null, "{}", "{}");
        when(message.getJMSMessageID()).thenReturn("ID:dlq-1");
        when(repository.findByMessageId("ID:dlq-1")).thenReturn(Optional.of(existing));

        DeadLetter stored = service().handle(message);

        assertThat(stored).isSameAs(existing);
        verify(repository, never()).save(any());
        verify(orders).failProcessing(42L, DeadLetterService.FAILURE_REASON);
    }

    @Test
    void storesPoisonMessagesWithoutTouchingAnyOrder() throws JMSException {
        when(message.getJMSMessageID()).thenReturn("ID:dlq-2");
        when(message.getJMSCorrelationID()).thenReturn("not-a-number");
        when(message.getJMSTimestamp()).thenReturn(0L);
        when(message.getText()).thenReturn("{not json");
        when(message.getPropertyNames()).thenReturn(Collections.enumeration(List.of("traceparent")));
        when(message.getObjectProperty("traceparent")).thenReturn("garbage");
        when(repository.findByMessageId("ID:dlq-2")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DeadLetter stored = service().handle(message);

        assertThat(stored.getOrderId()).isNull();
        assertThat(stored.getCorrelationId()).isEqualTo("not-a-number");
        assertThat(stored.getTraceId()).isNull();
        assertThat(stored.getSentAt()).isNull();
        assertThat(stored.getBody()).isEqualTo("{not json");
        verify(orders, never()).failProcessing(any(), any());
    }

    @Test
    void readsABinaryBodyAsText(@Mock BytesMessage bytes) throws JMSException {
        byte[] payload = "binary body".getBytes(StandardCharsets.UTF_8);
        when(bytes.getJMSMessageID()).thenReturn("ID:dlq-3");
        when(bytes.getJMSCorrelationID()).thenReturn(null);
        when(bytes.getJMSTimestamp()).thenReturn(0L);
        when(bytes.getPropertyNames()).thenReturn(Collections.emptyEnumeration());
        when(bytes.getBodyLength()).thenReturn((long) payload.length);
        when(bytes.readBytes(any())).thenAnswer(invocation -> {
            byte[] target = invocation.getArgument(0);
            System.arraycopy(payload, 0, target, 0, payload.length);
            return payload.length;
        });

        DeadLetter deadLetter = service().deadLetterFrom(bytes);

        assertThat(deadLetter.getBody()).isEqualTo("binary body");
        assertThat(deadLetter.getProperties()).isEqualTo("{}");
    }
}
