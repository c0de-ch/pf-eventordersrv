package ch.c0de.pf.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

    @Test
    void startsPendingWithoutAttempts() {
        OutboxEvent event = OutboxEvent.of("Order", 1L, "OrderAccepted", "{}", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        assertThat(event.isPublished()).isFalse();
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getLastError()).isNull();
        assertThat(event.getTraceContext()).startsWith("00-0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void publishingRecordsTimeAndClearsLastError() {
        OutboxEvent event = OutboxEvent.of("Order", 1L, "OrderAccepted", "{}", null);
        event.markFailed("broker down");
        Instant now = Instant.parse("2026-10-22T10:00:00Z");

        event.markPublished(now);

        assertThat(event.isPublished()).isTrue();
        assertThat(event.getPublishedAt()).isEqualTo(now);
        assertThat(event.getAttempts()).isEqualTo(2);
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void failureCountsAttemptAndTruncatesError() {
        OutboxEvent event = OutboxEvent.of("Order", 1L, "OrderAccepted", "{}", null);

        event.markFailed("x".repeat(1500));

        assertThat(event.isPublished()).isFalse();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).hasSize(OutboxEvent.MAX_ERROR_LENGTH);
    }
}
