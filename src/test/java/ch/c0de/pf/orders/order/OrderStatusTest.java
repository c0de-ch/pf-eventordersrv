package ch.c0de.pf.orders.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderStatusTest {

    @ParameterizedTest
    @CsvSource({"ACCEPTED,PROCESSING", "ACCEPTED,FAILED", "PROCESSING,PROCESSED", "PROCESSING,FAILED"})
    void allowsForwardTransitions(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"ACCEPTED,PROCESSED", "ACCEPTED,ACCEPTED", "PROCESSING,ACCEPTED", "PROCESSED,PROCESSING",
        "PROCESSED,FAILED", "FAILED,PROCESSING", "FAILED,PROCESSED", "FAILED,ACCEPTED"})
    void rejectsEveryOtherTransition(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @Test
    void onlyProcessedAndFailedAreFinal() {
        assertThat(OrderStatus.PROCESSED.isFinal()).isTrue();
        assertThat(OrderStatus.FAILED.isFinal()).isTrue();
        assertThat(OrderStatus.ACCEPTED.isFinal()).isFalse();
        assertThat(OrderStatus.PROCESSING.isFinal()).isFalse();
    }
}
