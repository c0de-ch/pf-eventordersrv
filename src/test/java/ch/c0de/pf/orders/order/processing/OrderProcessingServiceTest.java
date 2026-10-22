package ch.c0de.pf.orders.order.processing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.c0de.pf.orders.order.Order;
import ch.c0de.pf.orders.order.OrderService;
import ch.c0de.pf.orders.order.OrderTestData;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderProcessingServiceTest {

    @Mock
    OrderService orders;

    @Mock
    OrderProcessor processor;

    @InjectMocks
    OrderProcessingService service;

    @Test
    void processesOrderBetweenTwoTransitions() {
        Order order = OrderTestData.persistedOrder(7L);
        when(orders.startProcessing(7L)).thenReturn(Optional.of(order));

        service.process(7L);

        InOrder steps = inOrder(orders, processor);
        steps.verify(orders).startProcessing(7L);
        steps.verify(processor).process(order);
        steps.verify(orders).completeProcessing(7L);
    }

    @Test
    void doesNothingWhenThereIsNothingToProcess() {
        when(orders.startProcessing(7L)).thenReturn(Optional.empty());

        service.process(7L);

        verify(processor, never()).process(any());
        verify(orders, never()).completeProcessing(anyLong());
    }

    @Test
    void propagatesProcessingFailureWithoutCompleting() {
        Order order = OrderTestData.persistedOrder(7L);
        when(orders.startProcessing(7L)).thenReturn(Optional.of(order));
        doThrow(new OrderProcessingException("boom")).when(processor).process(order);

        assertThatThrownBy(() -> service.process(7L)).isInstanceOf(OrderProcessingException.class);

        verify(orders, never()).completeProcessing(anyLong());
    }
}
