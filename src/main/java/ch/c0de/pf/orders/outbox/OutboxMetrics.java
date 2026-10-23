package ch.c0de.pf.orders.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {

    private final Counter publishFailures;

    public OutboxMetrics(MeterRegistry registry, OutboxRepository repository) {
        Gauge.builder("outbox.pending", repository, OutboxRepository::countByPublishedAtIsNull)
            .description("Events waiting to be published").register(registry);
        this.publishFailures = Counter.builder("outbox.publish.failures")
            .description("Publish attempts that failed").register(registry);
    }

    void publishFailed() {
        publishFailures.increment();
    }
}
