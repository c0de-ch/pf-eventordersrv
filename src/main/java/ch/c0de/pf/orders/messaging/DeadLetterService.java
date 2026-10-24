package ch.c0de.pf.orders.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.c0de.pf.orders.order.OrderService;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DeadLetterService {

    static final String FAILURE_REASON = "dead-lettered after repeated processing failures";
    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    private final DeadLetterRepository deadLetters;
    private final OrderService orders;
    private final JsonMapper json;

    public DeadLetterService(DeadLetterRepository deadLetters, OrderService orders, JsonMapper json) {
        this.deadLetters = deadLetters;
        this.orders = orders;
        this.json = json;
    }

    // Evidence and status change in one transaction; a redelivered dead letter is recognised by its message id
    @Transactional
    public DeadLetter handle(Message message) throws JMSException {
        Optional<DeadLetter> existing = deadLetters.findByMessageId(message.getJMSMessageID());
        DeadLetter stored = existing.isPresent() ? existing.get() : deadLetters.save(deadLetterFrom(message));
        if (stored.getOrderId() != null) {
            orders.failProcessing(stored.getOrderId(), FAILURE_REASON);
        }
        return stored;
    }

    DeadLetter deadLetterFrom(Message message) throws JMSException {
        Map<String, String> properties = properties(message);
        String correlationId = message.getJMSCorrelationID();
        return new DeadLetter(
            parseLong(correlationId),
            parseLong(properties.get(JmsOutboxPublisher.EVENT_ID_PROPERTY)),
            properties.get(JmsOutboxPublisher.TYPE_PROPERTY),
            message.getJMSMessageID(),
            properties.get("_AMQ_ORIG_MESSAGE_ID"),
            properties.get("_AMQ_ORIG_QUEUE"),
            correlationId,
            traceId(properties.get("traceparent")),
            message.getJMSTimestamp() == 0 ? null : Instant.ofEpochMilli(message.getJMSTimestamp()),
            body(message),
            json.writeValueAsString(properties));
    }

    private static Map<String, String> properties(Message message) throws JMSException {
        Map<String, String> properties = new TreeMap<>();
        Enumeration<?> names = message.getPropertyNames();
        while (names.hasMoreElements()) {
            String name = String.valueOf(names.nextElement());
            properties.put(name, String.valueOf(message.getObjectProperty(name)));
        }
        return properties;
    }

    // Text as sent; a binary body is read as UTF-8 up to the stored length; other types leave the body empty
    private static String body(Message message) throws JMSException {
        return switch (message) {
            case TextMessage text -> text.getText();
            case BytesMessage bytes -> {
                byte[] buffer = new byte[(int) Math.min(bytes.getBodyLength(), DeadLetter.MAX_BODY_LENGTH)];
                bytes.readBytes(buffer);
                yield new String(buffer, StandardCharsets.UTF_8);
            }
            default -> null;
        };
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String traceId(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        Matcher matcher = TRACEPARENT.matcher(traceparent);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
