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

    public OutboxRelay(OutboxRepository outbox, OutboxPublisher publisher, PlatformTransactionManager transactionManager,
                       Clock clock, OutboxRelayProperties properties) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = properties.batchSize();
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
            publisher.publish(event);
            event.markPublished(clock.instant());
            log.info("order event published eventId={} eventType={} aggregateId={}",
                event.getId(), event.getEventType(), event.getAggregateId());
            return true;
        } catch (RuntimeException e) {
            event.markFailed(e.toString());
            log.warn("order event publish failed eventId={} attempts={} error={}", event.getId(), event.getAttempts(), e.toString());
            return false;
        }
    }
}
