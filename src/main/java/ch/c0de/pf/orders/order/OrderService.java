package ch.c0de.pf.orders.order;

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

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;

    public OrderService(OrderRepository orders) {
        this.orders = orders;
    }

    public Order create(CreateOrder command) {
        String currency = command.currency() != null ? command.currency() : DEFAULT_CURRENCY;
        Order order = orders.save(Order.create(command.customerId(), command.amount(), currency));
        log.info("order created orderId={} customerId={} amount={} currency={}",
            order.getId(), order.getCustomerId(), order.getAmount(), order.getCurrency());
        return order;
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
