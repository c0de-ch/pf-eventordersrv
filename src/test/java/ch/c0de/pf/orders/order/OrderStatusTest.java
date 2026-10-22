package ch.c0de.pf.orders.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderStatusTest {

    @ParameterizedTest
    @CsvSource({"CREATED,PROCESSING", "CREATED,FAILED", "PROCESSING,PROCESSED", "PROCESSING,FAILED"})
    void allowsForwardTransitions(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"CREATED,PROCESSED", "CREATED,CREATED", "PROCESSING,CREATED", "PROCESSED,PROCESSING",
        "PROCESSED,FAILED", "FAILED,PROCESSING", "FAILED,PROCESSED", "FAILED,CREATED"})
    void rejectsEveryOtherTransition(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @Test
    void onlyProcessedAndFailedAreFinal() {
        assertThat(OrderStatus.PROCESSED.isFinal()).isTrue();
        assertThat(OrderStatus.FAILED.isFinal()).isTrue();
        assertThat(OrderStatus.CREATED.isFinal()).isFalse();
        assertThat(OrderStatus.PROCESSING.isFinal()).isFalse();
    }
}
