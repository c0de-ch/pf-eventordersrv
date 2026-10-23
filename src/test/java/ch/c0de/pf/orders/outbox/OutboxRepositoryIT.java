package ch.c0de.pf.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import ch.c0de.pf.orders.PostgresContainerConfiguration;
import ch.c0de.pf.orders.common.ClockConfig;
import ch.c0de.pf.orders.common.ClockProperties;
import ch.c0de.pf.orders.common.JpaAuditingConfig;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// No test-managed transaction: the lock behaviour needs committed rows and two real transactions
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({PostgresContainerConfiguration.class, JpaAuditingConfig.class, ClockConfig.class})
@EnableConfigurationProperties(ClockProperties.class)
class OutboxRepositoryIT {

    @Autowired
    OutboxRepository repository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void locksOldestPendingRowsWithinLimit() {
        OutboxEvent first = repository.save(pending(1L));
        OutboxEvent published = repository.save(pending(2L));
        OutboxEvent third = repository.save(pending(3L));
        published.markPublished(Instant.parse("2026-10-22T10:00:00Z"));
        repository.save(published);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        List<Long> all = transaction.execute(status -> ids(repository.lockPending(10)));
        List<Long> limited = transaction.execute(status -> ids(repository.lockPending(1)));

        assertThat(all).containsExactly(first.getId(), third.getId());
        assertThat(limited).containsExactly(first.getId());
        assertThat(repository.countByPublishedAtIsNull()).isEqualTo(2);
    }

    @Test
    void skipsRowsLockedByAnotherTransaction() throws Exception {
        OutboxEvent first = repository.save(pending(1L));
        OutboxEvent second = repository.save(pending(2L));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ExecutorService otherRelay = Executors.newSingleThreadExecutor();
        try {
            List<Long> seenByOther = transaction.execute(status -> {
                assertThat(ids(repository.lockPending(1))).containsExactly(first.getId());
                try {
                    return otherRelay.submit(() -> transaction.execute(inner -> ids(repository.lockPending(10))))
                        .get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertThat(seenByOther).containsExactly(second.getId());
        } finally {
            otherRelay.shutdownNow();
        }
    }

    private static OutboxEvent pending(long aggregateId) {
        return OutboxEvent.of("Order", aggregateId, "OrderAccepted", "{\"orderId\": " + aggregateId + "}", null);
    }

    private static List<Long> ids(List<OutboxEvent> events) {
        return events.stream().map(OutboxEvent::getId).toList();
    }
}
