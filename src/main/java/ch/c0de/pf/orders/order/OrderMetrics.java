package ch.c0de.pf.orders.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class OrderMetrics {

    private final Clock clock;
    private final Counter created;
    private final Counter processed;
    private final Counter failed;
    private final Counter deadLettered;
    private final Timer lifecycleProcessed;
    private final Timer lifecycleFailed;

    public OrderMetrics(MeterRegistry registry, Clock clock) {
        this.clock = clock;
        // not "orders.created": the Prometheus client treats a _created suffix as reserved and strips it
        this.created = Counter.builder("orders.accepted").description("Orders accepted").register(registry);
        this.processed = Counter.builder("orders.processed").description("Orders processed successfully").register(registry);
        this.failed = Counter.builder("orders.failed").description("Orders that ended in FAILED").register(registry);
        this.deadLettered = Counter.builder("orders.dead_lettered")
            .description("order.accepted messages that reached the dead-letter queue").register(registry);
        this.lifecycleProcessed = lifecycle(registry, "processed");
        this.lifecycleFailed = lifecycle(registry, "failed");
    }

    private static Timer lifecycle(MeterRegistry registry, String outcome) {
        return Timer.builder("order.lifecycle").description("Time from creation to a final status")
            .tag("outcome", outcome).register(registry);
    }

    void created() {
        created.increment();
    }

    void processed(Order order) {
        processed.increment();
        recordLifecycle(lifecycleProcessed, order);
    }

    void failed(Order order) {
        failed.increment();
        recordLifecycle(lifecycleFailed, order);
    }

    public void deadLettered() {
        deadLettered.increment();
    }

    private void recordLifecycle(Timer timer, Order order) {
        if (order.getCreatedAt() != null) {
            timer.record(Duration.between(order.getCreatedAt(), clock.instant()));
        }
    }
}
