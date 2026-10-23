package ch.c0de.pf.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    @Mock
    OutboxRepository repository;

    @Mock
    OutboxPublisher publisher;

    OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(repository, publisher, new NoOpTransactionManager(), Clock.fixed(NOW, ZoneOffset.UTC),
            new OutboxRelayProperties(true, Duration.ofSeconds(1), 2), new OutboxMetrics(new SimpleMeterRegistry(), repository),
            new OutboxTracing(Tracer.NOOP, Propagator.NOOP));
    }

    @Test
    void publishesPendingEventsAndMarksThem() {
        OutboxEvent first = pending();
        OutboxEvent second = pending();
        when(repository.lockPending(2)).thenReturn(List.of(first, second));

        int published = relay.publishBatch();

        assertThat(published).isEqualTo(2);
        verify(publisher).publish(first);
        verify(publisher).publish(second);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.isPublished()).isTrue();
    }

    @Test
    void keepsFailedEventPendingAndContinuesWithTheNext() {
        OutboxEvent failing = pending();
        OutboxEvent next = pending();
        when(repository.lockPending(2)).thenReturn(List.of(failing, next));
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(failing);

        int published = relay.publishBatch();

        assertThat(published).isEqualTo(1);
        assertThat(failing.isPublished()).isFalse();
        assertThat(failing.getAttempts()).isEqualTo(1);
        assertThat(failing.getLastError()).contains("broker down");
        assertThat(next.isPublished()).isTrue();
    }

    @Test
    void pollDrainsFullBatchesBeforeWaiting() {
        when(repository.lockPending(2))
            .thenReturn(List.of(pending(), pending()))
            .thenReturn(List.of(pending()));

        relay.poll();

        verify(repository, times(2)).lockPending(2);
        verify(publisher, times(3)).publish(any());
    }

    @Test
    void pollSurvivesDatabaseOutage() {
        when(repository.lockPending(2)).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> relay.poll()).doesNotThrowAnyException();
    }

    private static OutboxEvent pending() {
        return OutboxEvent.of("Order", 1L, "OrderAccepted", "{}", null);
    }

    static final class NoOpTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
