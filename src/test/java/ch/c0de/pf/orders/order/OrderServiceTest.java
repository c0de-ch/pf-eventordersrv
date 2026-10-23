package ch.c0de.pf.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.c0de.pf.orders.outbox.Outbox;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    @Mock
    OrderRepository orders;

    @Mock
    Outbox outbox;

    OrderService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new OrderService(orders, outbox, clock, new OrderMetrics(new SimpleMeterRegistry(), clock));
    }

    @Test
    void createsOrderWithDefaultCurrency() {
        when(orders.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order order = service.create(new CreateOrder("12345", new BigDecimal("99.99"), null));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(order.getCurrency()).isEqualTo("CHF");
        verify(orders).save(order);
    }

    @Test
    void appendsOrderAcceptedEventToTheOutbox() {
        when(orders.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new CreateOrder("12345", new BigDecimal("99.99"), "CHF"));

        ArgumentCaptor<OrderAcceptedEvent> event = ArgumentCaptor.forClass(OrderAcceptedEvent.class);
        verify(outbox).append(eq("Order"), any(), eq("OrderAccepted"), event.capture());
        assertThat(event.getValue().customerId()).isEqualTo("12345");
        assertThat(event.getValue().amount()).isEqualByComparingTo("99.99");
        assertThat(event.getValue().occurredAt()).isEqualTo(NOW);
    }

    @Test
    void keepsRequestedCurrency() {
        when(orders.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order order = service.create(new CreateOrder("12345", new BigDecimal("10.00"), "EUR"));

        assertThat(order.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void getReturnsOrderWithHistory() {
        Order order = OrderTestData.newOrder();
        when(orders.findWithHistoryById(42L)).thenReturn(Optional.of(order));

        assertThat(service.get(42L)).isSameAs(order);
    }

    @Test
    void findDelegatesFilterAndPaging() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Order> page = new PageImpl<>(List.of(OrderTestData.newOrder()));
        when(orders.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        assertThat(service.find(new OrderFilter("12345", OrderStatus.ACCEPTED), pageable)).isSameAs(page);
    }

    @Test
    void startsProcessingOfCreatedOrder() {
        Order order = OrderTestData.persistedOrder(7L);
        when(orders.findById(7L)).thenReturn(Optional.of(order));

        assertThat(service.startProcessing(7L)).contains(order);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PROCESSING);
    }

    @Test
    void resumesOrderAlreadyInProcessing() {
        Order order = OrderTestData.persistedOrder(7L);
        order.markProcessing();
        when(orders.findById(7L)).thenReturn(Optional.of(order));

        assertThat(service.startProcessing(7L)).contains(order);
        assertThat(order.getHistory()).hasSize(2);
    }

    @Test
    void skipsProcessingOfFinalAndUnknownOrders() {
        Order processed = OrderTestData.persistedOrder(7L);
        processed.markProcessing();
        processed.markProcessed();
        when(orders.findById(7L)).thenReturn(Optional.of(processed));
        when(orders.findById(8L)).thenReturn(Optional.empty());

        assertThat(service.startProcessing(7L)).isEmpty();
        assertThat(service.startProcessing(8L)).isEmpty();
    }

    @Test
    void completesProcessing() {
        Order order = OrderTestData.persistedOrder(7L);
        order.markProcessing();
        when(orders.findById(7L)).thenReturn(Optional.of(order));

        service.completeProcessing(7L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PROCESSED);
    }

    @Test
    void failsProcessingWithReasonOnlyWhileNotFinal() {
        Order processing = OrderTestData.persistedOrder(7L);
        processing.markProcessing();
        Order processed = OrderTestData.persistedOrder(8L);
        processed.markProcessing();
        processed.markProcessed();
        when(orders.findById(7L)).thenReturn(Optional.of(processing));
        when(orders.findById(8L)).thenReturn(Optional.of(processed));
        when(orders.findById(9L)).thenReturn(Optional.empty());

        service.failProcessing(7L, "dead-lettered");
        service.failProcessing(8L, "dead-lettered");
        service.failProcessing(9L, "dead-lettered");

        assertThat(processing.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(processing.getHistory().getLast().getReason()).isEqualTo("dead-lettered");
        assertThat(processed.getStatus()).isEqualTo(OrderStatus.PROCESSED);
    }

    @Test
    void getFailsForUnknownOrder() {
        when(orders.findWithHistoryById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(42L))
            .isInstanceOf(OrderNotFoundException.class)
            .hasMessage("order 42 not found");
    }
}
