package ch.c0de.pf.orders.order;

import ch.c0de.pf.orders.outbox.Outbox;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrderService {

    static final String DEFAULT_CURRENCY = "CHF";
    static final String AGGREGATE_TYPE = "Order";

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final Outbox outbox;
    private final Clock clock;

    public OrderService(OrderRepository orders, Outbox outbox, Clock clock) {
        this.orders = orders;
        this.outbox = outbox;
        this.clock = clock;
    }

    // Order and its event are written in one transaction; the relay publishes the event afterwards
    public Order create(CreateOrder command) {
        String currency = command.currency() != null ? command.currency() : DEFAULT_CURRENCY;
        Order order = orders.save(Order.create(command.customerId(), command.amount(), currency));
        outbox.append(AGGREGATE_TYPE, order.getId(), OrderCreatedEvent.TYPE, OrderCreatedEvent.of(order, clock.instant()));
        log.info("order created orderId={} customerId={} amount={} currency={}",
            order.getId(), order.getCustomerId(), order.getAmount(), order.getCurrency());
        return order;
    }

    // Empty result means "nothing to do": unknown order (poison message) or already final (duplicate delivery)
    public Optional<Order> startProcessing(Long id) {
        Optional<Order> found = orders.findById(id);
        if (found.isEmpty()) {
            log.warn("order not found for processing orderId={}", id);
            return Optional.empty();
        }
        Order order = found.get();
        if (order.getStatus().isFinal()) {
            log.info("order already final, skipping orderId={} status={}", id, order.getStatus());
            return Optional.empty();
        }
        if (order.getStatus() == OrderStatus.CREATED) {
            order.markProcessing();
            log.info("order processing started orderId={}", id);
        } else {
            log.info("order processing resumed orderId={}", id);
        }
        return Optional.of(order);
    }

    public void completeProcessing(Long id) {
        Order order = orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        order.markProcessed();
        log.info("order processed orderId={}", id);
    }

    public void failProcessing(Long id, String reason) {
        orders.findById(id).ifPresentOrElse(order -> {
            if (order.getStatus().isFinal()) {
                log.info("order already final, ignoring failure orderId={} status={}", id, order.getStatus());
                return;
            }
            order.markFailed(reason);
            log.warn("order failed orderId={} reason={}", id, reason);
        }, () -> log.warn("order not found for failure orderId={}", id));
    }

    @Transactional(readOnly = true)
    public Order get(Long id) {
        return orders.findWithHistoryById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Order> find(OrderFilter filter, Pageable pageable) {
        return orders.findAll(filter.toSpecification(), pageable);
    }
}
