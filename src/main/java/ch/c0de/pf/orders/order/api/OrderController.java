package ch.c0de.pf.orders.order.api;

import ch.c0de.pf.orders.order.Order;
import ch.c0de.pf.orders.order.OrderFilter;
import ch.c0de.pf.orders.order.OrderService;
import ch.c0de.pf.orders.order.OrderStatus;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request, UriComponentsBuilder uriBuilder) {
        Order order = orders.create(request.toCommand());
        URI location = uriBuilder.path("/orders/{id}").build(order.getId());
        return ResponseEntity.created(location).body(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return OrderResponse.from(orders.get(id));
    }

    @GetMapping
    public PagedModel<OrderSummary> list(
        @RequestParam(required = false) String customerId,
        @RequestParam(required = false) OrderStatus status,
        @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return new PagedModel<>(orders.find(new OrderFilter(customerId, status), pageable).map(OrderSummary::from));
    }
}
