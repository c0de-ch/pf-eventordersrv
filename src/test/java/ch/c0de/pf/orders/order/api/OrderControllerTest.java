package ch.c0de.pf.orders.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.c0de.pf.orders.order.CreateOrder;
import ch.c0de.pf.orders.order.IllegalStatusTransitionException;
import ch.c0de.pf.orders.order.Order;
import ch.c0de.pf.orders.order.OrderFilter;
import ch.c0de.pf.orders.order.OrderNotFoundException;
import ch.c0de.pf.orders.order.OrderService;
import ch.c0de.pf.orders.order.OrderStatus;
import ch.c0de.pf.orders.order.OrderTestData;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    OrderService service;

    @Test
    void createsOrderAndReturnsLocation() {
        when(service.create(new CreateOrder("12345", new BigDecimal("99.99"), null)))
            .thenReturn(OrderTestData.persistedOrder(42L));

        MvcTestResult result = mvc.post().uri("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"customerId": "12345", "amount": 99.99}
                """)
            .exchange();

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("http://localhost/orders/42");
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(42);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CREATED");
        assertThat(result).bodyJson().extractingPath("$.history[0].to").isEqualTo("CREATED");
    }

    @Test
    void rejectsInvalidRequestWithFieldErrors() {
        MvcTestResult result = mvc.post().uri("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"customerId": "", "amount": -1, "currency": "chf"}
                """)
            .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("amount", "currency", "customerId");
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedJson() {
        MvcTestResult result = mvc.post().uri("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{not json")
            .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void returnsOrderWithHistory() {
        Order order = OrderTestData.persistedOrder(7L);
        order.markProcessing();
        when(service.get(7L)).thenReturn(order);

        MvcTestResult result = mvc.get().uri("/orders/7").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PROCESSING");
        assertThat(result).bodyJson().extractingPath("$.history[*].to").asArray().containsExactly("CREATED", "PROCESSING");
    }

    @Test
    void returnsProblemForUnknownOrder() {
        when(service.get(99L)).thenThrow(new OrderNotFoundException(99L));

        MvcTestResult result = mvc.get().uri("/orders/99").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Order not found");
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("order 99 not found");
    }

    @Test
    void mapsIllegalTransitionToConflict() {
        when(service.get(7L)).thenThrow(new IllegalStatusTransitionException(7L, OrderStatus.PROCESSED, OrderStatus.FAILED));

        MvcTestResult result = mvc.get().uri("/orders/7").exchange();

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Illegal status transition");
    }

    @Test
    void rejectsNonNumericId() {
        assertThat(mvc.get().uri("/orders/abc")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listsOrdersAsPage() {
        when(service.find(eq(new OrderFilter("12345", OrderStatus.CREATED)), any()))
            .thenReturn(new PageImpl<>(List.of(OrderTestData.persistedOrder(1L)), PageRequest.of(0, 20), 1));

        MvcTestResult result = mvc.get().uri("/orders?customerId=12345&status=CREATED").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.content[0].id").isEqualTo(1);
        assertThat(result).bodyJson().doesNotHavePath("$.content[0].history");
        assertThat(result).bodyJson().extractingPath("$.page.totalElements").isEqualTo(1);
    }

    @Test
    void rejectsUnknownStatusFilter() {
        assertThat(mvc.get().uri("/orders?status=DONE")).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
