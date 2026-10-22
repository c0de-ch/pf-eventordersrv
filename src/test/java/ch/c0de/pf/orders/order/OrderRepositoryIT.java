package ch.c0de.pf.orders.order;

import static ch.c0de.pf.orders.order.OrderStatus.CREATED;
import static ch.c0de.pf.orders.order.OrderStatus.FAILED;
import static ch.c0de.pf.orders.order.OrderStatus.PROCESSING;
import static org.assertj.core.api.Assertions.assertThat;

import ch.c0de.pf.orders.PostgresContainerConfiguration;
import ch.c0de.pf.orders.common.JpaAuditingConfig;
import ch.c0de.pf.orders.common.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({PostgresContainerConfiguration.class, JpaAuditingConfig.class, OrderRepositoryIT.ClockConfiguration.class})
class OrderRepositoryIT {

    static final Instant T0 = Instant.parse("2026-10-22T10:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        MutableClock clock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    OrderRepository orders;

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(T0);
    }

    @Test
    void assignsSequentialIdsAndAuditColumns() {
        Order first = orders.saveAndFlush(OrderTestData.newOrder());
        Order second = orders.saveAndFlush(OrderTestData.newOrder());

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isGreaterThan(first.getId());
        assertThat(first.getCreatedAt()).isEqualTo(T0);
        assertThat(first.getUpdatedAt()).isEqualTo(T0);
        assertThat(first.getVersion()).isZero();
    }

    @Test
    void bumpsUpdatedAtAndVersionOnTransition() {
        Order order = orders.saveAndFlush(OrderTestData.newOrder());
        clock.advance(Duration.ofSeconds(5));

        order.markProcessing();
        orders.saveAndFlush(order);
        entityManager.clear();

        Order reloaded = orders.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PROCESSING);
        assertThat(reloaded.getCreatedAt()).isEqualTo(T0);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    @Test
    void loadsHistoryInTransitionOrder() {
        Order order = OrderTestData.newOrder();
        order.markProcessing();
        order.markFailed("downstream timeout");
        Long id = orders.saveAndFlush(order).getId();
        entityManager.clear();

        Order reloaded = orders.findWithHistoryById(id).orElseThrow();

        assertThat(reloaded.getHistory()).extracting(OrderStatusHistory::getToStatus).containsExactly(CREATED, PROCESSING, FAILED);
        assertThat(reloaded.getHistory()).extracting(OrderStatusHistory::getCreatedAt).containsOnly(T0);
        assertThat(reloaded.getHistory().getLast().getReason()).isEqualTo("downstream timeout");
    }
}
