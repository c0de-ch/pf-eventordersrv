package ch.c0de.pf.orders.order;

import static ch.c0de.pf.orders.order.OrderStatus.ACCEPTED;
import static ch.c0de.pf.orders.order.OrderStatus.FAILED;
import static ch.c0de.pf.orders.order.OrderStatus.PROCESSED;
import static ch.c0de.pf.orders.order.OrderStatus.PROCESSING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OrderTest {

    @Test
    void createsOrderInStatusAcceptedWithFirstHistoryEntry() {
        Order order = Order.create("12345", new BigDecimal("99.9"), "CHF");

        assertThat(order.getStatus()).isEqualTo(ACCEPTED);
        assertThat(order.getAmount()).isEqualTo(new BigDecimal("99.90"));
        assertThat(order.getHistory()).singleElement().satisfies(entry -> {
            assertThat(entry.getFromStatus()).isNull();
            assertThat(entry.getToStatus()).isEqualTo(ACCEPTED);
            assertThat(entry.getReason()).isNull();
        });
    }

    @Test
    void recordsEveryTransitionInHistory() {
        Order order = OrderTestData.newOrder();

        order.markProcessing();
        order.markProcessed();

        assertThat(order.getStatus()).isEqualTo(PROCESSED);
        assertThat(order.getHistory()).extracting(OrderStatusHistory::getFromStatus).containsExactly(null, ACCEPTED, PROCESSING);
        assertThat(order.getHistory()).extracting(OrderStatusHistory::getToStatus).containsExactly(ACCEPTED, PROCESSING, PROCESSED);
    }

    @Test
    void keepsFailureReason() {
        Order order = OrderTestData.newOrder();
        order.markProcessing();

        order.markFailed("downstream timeout");

        assertThat(order.getStatus()).isEqualTo(FAILED);
        assertThat(order.getHistory().getLast().getReason()).isEqualTo("downstream timeout");
    }

    @Test
    void truncatesLongFailureReasons() {
        Order order = OrderTestData.newOrder();

        order.markFailed("x".repeat(400));

        assertThat(order.getHistory().getLast().getReason()).hasSize(OrderStatusHistory.MAX_REASON_LENGTH);
    }

    @Test
    void rejectsSkippingProcessing() {
        Order order = OrderTestData.newOrder();

        assertThatThrownBy(order::markProcessed)
            .isInstanceOf(IllegalStatusTransitionException.class)
            .hasMessageContaining("ACCEPTED -> PROCESSED");
        assertThat(order.getStatus()).isEqualTo(ACCEPTED);
        assertThat(order.getHistory()).hasSize(1);
    }

    @Test
    void rejectsTransitionsOutOfFinalStatus() {
        Order order = OrderTestData.newOrder();
        order.markProcessing();
        order.markProcessed();

        assertThatThrownBy(() -> order.markFailed("too late")).isInstanceOf(IllegalStatusTransitionException.class);
        assertThat(order.getStatus()).isEqualTo(PROCESSED);
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> Order.create("12345", BigDecimal.ZERO, "CHF"))
            .withMessageContaining("positive");
    }

    @Test
    void rejectsMoreThanTwoDecimals() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> Order.create("12345", new BigDecimal("1.005"), "CHF"))
            .withMessageContaining("two decimals");
    }
}
