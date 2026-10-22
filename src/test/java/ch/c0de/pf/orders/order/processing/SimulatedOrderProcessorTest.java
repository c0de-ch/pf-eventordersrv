package ch.c0de.pf.orders.order.processing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.c0de.pf.orders.order.OrderTestData;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SimulatedOrderProcessorTest {

    final SimulatedOrderProcessor processor = new SimulatedOrderProcessor(new ProcessingProperties(Duration.ZERO, "FAIL-", "FLAKY-"));

    @Test
    void processesRegularOrders() {
        assertThatCode(() -> processor.process(OrderTestData.persistedOrder(1L))).doesNotThrowAnyException();
    }

    @Test
    void failsPermanentlyForFailingCustomers() {
        assertThatThrownBy(() -> processor.process(OrderTestData.persistedOrder(2L, "FAIL-1")))
            .isInstanceOf(OrderProcessingException.class)
            .isNotInstanceOf(TransientProcessingException.class);
    }

    @Test
    void failsOnlyOnceForFlakyCustomers() {
        assertThatThrownBy(() -> processor.process(OrderTestData.persistedOrder(3L, "FLAKY-1")))
            .isInstanceOf(TransientProcessingException.class);
        assertThatCode(() -> processor.process(OrderTestData.persistedOrder(3L, "FLAKY-1"))).doesNotThrowAnyException();
    }
}
