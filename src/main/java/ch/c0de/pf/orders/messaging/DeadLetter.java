package ch.c0de.pf.orders.messaging;

import java.time.Instant;

import ch.c0de.pf.orders.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dead_letter")
public class DeadLetter extends AuditedEntity {

    static final int MAX_BODY_LENGTH = 65_536;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", updatable = false)
    private Long orderId;

    @Column(name = "event_id", updatable = false)
    private Long eventId;

    @Column(name = "event_type", length = 64, updatable = false)
    private String eventType;

    @Column(name = "message_id", nullable = false, updatable = false)
    private String messageId;

    @Column(name = "original_message_id", updatable = false)
    private String originalMessageId;

    @Column(name = "origin_queue", updatable = false)
    private String originQueue;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "trace_id", length = 32, updatable = false)
    private String traceId;

    @Column(name = "sent_at", updatable = false)
    private Instant sentAt;

    @Column(updatable = false)
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String properties;

    protected DeadLetter() {
    }

    DeadLetter(Long orderId, Long eventId, String eventType, String messageId, String originalMessageId, String originQueue,
               String correlationId, String traceId, Instant sentAt, String body, String properties) {
        this.orderId = orderId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.messageId = messageId;
        this.originalMessageId = originalMessageId;
        this.originQueue = originQueue;
        this.correlationId = correlationId;
        this.traceId = traceId;
        this.sentAt = sentAt;
        this.body = body == null || body.length() <= MAX_BODY_LENGTH ? body : body.substring(0, MAX_BODY_LENGTH);
        this.properties = properties;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getOriginalMessageId() {
        return originalMessageId;
    }

    public String getOriginQueue() {
        return originQueue;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getTraceId() {
        return traceId;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getBody() {
        return body;
    }

    public String getProperties() {
        return properties;
    }
}
