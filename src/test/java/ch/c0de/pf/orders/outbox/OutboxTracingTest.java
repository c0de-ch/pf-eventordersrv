package ch.c0de.pf.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutboxTracingTest {

    final OutboxTracing tracing = new OutboxTracing(Tracer.NOOP, Propagator.NOOP);

    @Test
    void capturesNothingWithoutActiveTrace() {
        assertThat(tracing.currentTraceContext()).isNull();
    }

    @Test
    void publishesWithAndWithoutStoredContext() {
        AtomicInteger published = new AtomicInteger();

        tracing.publishWithinTrace(OutboxEvent.of("Order", 1L, "OrderAccepted", "{}", null), published::incrementAndGet);
        tracing.publishWithinTrace(OutboxEvent.of("Order", 2L, "OrderAccepted", "{}",
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"), published::incrementAndGet);

        assertThat(published).hasValue(2);
    }

    @Test
    void propagatesPublishFailures() {
        OutboxEvent event = OutboxEvent.of("Order", 3L, "OrderAccepted", "{}", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        assertThatThrownBy(() -> tracing.publishWithinTrace(event, () -> {
            throw new IllegalStateException("broker down");
        })).isInstanceOf(IllegalStateException.class);
    }
}
