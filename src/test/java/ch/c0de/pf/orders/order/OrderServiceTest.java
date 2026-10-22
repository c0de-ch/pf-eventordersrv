package ch.c0de.pf.orders.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    OrderRepository orders;

    @InjectMocks
    OrderService service;

    @Test
    void createsOrderWithDefaultCurrency() {
        when(orders.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order order = service.create(new CreateOrder("12345", new BigDecimal("99.99"), null));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getCurrency()).isEqualTo("CHF");
        verify(orders).save(order);
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
    void getFailsForUnknownOrder() {
        when(orders.findWithHistoryById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(42L))
            .isInstanceOf(OrderNotFoundException.class)
            .hasMessage("order 42 not found");
    }
}
