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
    private final OrderMetrics metrics;

    public OrderService(OrderRepository orders, Outbox outbox, Clock clock, OrderMetrics metrics) {
        this.orders = orders;
        this.outbox = outbox;
        this.clock = clock;
        this.metrics = metrics;
    }

    // Order and its event are written in one transaction; the relay publishes the event afterwards
    public Order create(CreateOrder command) {
        String currency = command.currency() != null ? command.currency() : DEFAULT_CURRENCY;
        Order order = orders.save(Order.create(command.customerId(), command.amount(), currency));
        outbox.append(AGGREGATE_TYPE, order.getId(), OrderAcceptedEvent.TYPE, OrderAcceptedEvent.of(order, clock.instant()));
        metrics.created();
        log.atInfo().addKeyValue("event.action", "order.accepted").addKeyValue("order.id", order.getId())
            .addKeyValue("customer.id", order.getCustomerId()).addKeyValue("order.amount", order.getAmount())
            .addKeyValue("order.currency", order.getCurrency()).log("order accepted");
        return order;
    }

    // Callers run inside an MDC scope with order.id/event.id, so these lines carry no explicit order.id.
    // Empty result means "nothing to do": unknown order (poison message) or already final (duplicate delivery)
    public Optional<Order> startProcessing(Long id) {
        Optional<Order> found = orders.findById(id);
        if (found.isEmpty()) {
            log.atWarn().addKeyValue("event.action", "order.processing.skipped").addKeyValue("reason", "not_found")
                .log("order not found for processing");
            return Optional.empty();
        }
        Order order = found.get();
        if (order.getStatus().isFinal()) {
            log.atInfo().addKeyValue("event.action", "order.processing.skipped").addKeyValue("reason", "already_final")
                .addKeyValue("order.status", order.getStatus()).log("order already final");
            return Optional.empty();
        }
        if (order.getStatus() == OrderStatus.ACCEPTED) {
            order.markProcessing();
            log.atInfo().addKeyValue("event.action", "order.processing.started").log("order processing started");
        } else {
            log.atInfo().addKeyValue("event.action", "order.processing.resumed").log("order processing resumed");
        }
        return Optional.of(order);
    }

    public void completeProcessing(Long id) {
        Order order = orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        order.markProcessed();
        metrics.processed(order);
        log.atInfo().addKeyValue("event.action", "order.processed").log("order processed");
    }

    public void failProcessing(Long id, String reason) {
        orders.findById(id).ifPresentOrElse(order -> {
            if (order.getStatus().isFinal()) {
                log.atInfo().addKeyValue("event.action", "order.failure.ignored").addKeyValue("order.status", order.getStatus())
                    .log("order already final, failure ignored");
                return;
            }
            order.markFailed(reason);
            metrics.failed(order);
            log.atWarn().addKeyValue("event.action", "order.failed").addKeyValue("reason", reason).log("order failed");
        }, () -> log.atWarn().addKeyValue("event.action", "order.failure.ignored").addKeyValue("reason", "not_found")
            .log("order not found for failure"));
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
