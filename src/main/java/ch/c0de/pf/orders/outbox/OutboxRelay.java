package ch.c0de.pf.orders.outbox;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(prefix = "orders.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final OutboxPublisher publisher;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int batchSize;
    private final OutboxMetrics metrics;
    private final OutboxTracing tracing;

    public OutboxRelay(OutboxRepository outbox, OutboxPublisher publisher, PlatformTransactionManager transactionManager,
                       Clock clock, OutboxRelayProperties properties, OutboxMetrics metrics, OutboxTracing tracing) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = properties.batchSize();
        this.metrics = metrics;
        this.tracing = tracing;
    }

    @Scheduled(fixedDelayString = "${orders.outbox.relay.poll-interval:PT1S}")
    public void poll() {
        try {
            int published;
            do {
                published = publishBatch();
            } while (published == batchSize);
        } catch (DataAccessException e) {
            log.warn("outbox poll failed error={}", e.toString());
        }
    }

    // One transaction per batch: the rows stay locked while their messages go out
    int publishBatch() {
        Integer published = transaction.execute(status -> {
            int count = 0;
            for (OutboxEvent event : outbox.lockPending(batchSize)) {
                if (publish(event)) {
                    count++;
                }
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    private boolean publish(OutboxEvent event) {
        try {
            tracing.publishWithinTrace(event, () -> {
                publisher.publish(event);
                event.markPublished(clock.instant());
                log.atInfo().addKeyValue("event.action", "order.event.published").addKeyValue("event.id", event.getId())
                    .addKeyValue("order.id", event.getAggregateId()).addKeyValue("event.type", event.getEventType())
                    .log("order event published");
            });
            return true;
        } catch (RuntimeException e) {
            event.markFailed(e.toString());
            metrics.publishFailed();
            log.atWarn().addKeyValue("event.action", "order.event.publish_failed").addKeyValue("event.id", event.getId())
                .addKeyValue("order.id", event.getAggregateId()).addKeyValue("attempts", event.getAttempts())
                .addKeyValue("error.type", e.getClass().getName()).addKeyValue("error.message", e.getMessage())
                .log("order event publish failed");
            return false;
        }
    }
}
