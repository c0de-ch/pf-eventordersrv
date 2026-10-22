package ch.c0de.pf.orders.order;

import ch.c0de.pf.orders.common.AuditedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "orders")
public class Order extends AuditedEntity {

    private static final int AMOUNT_SCALE = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, length = 64, updatable = false)
    private String customerId;

    @Column(nullable = false, precision = 19, scale = AMOUNT_SCALE, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Version
    private Long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    private List<OrderStatusHistory> history = new ArrayList<>();

    protected Order() {
    }

    private Order(String customerId, BigDecimal amount, String currency) {
        this.customerId = customerId;
        this.amount = amount;
        this.currency = currency;
        this.status = OrderStatus.CREATED;
        this.history.add(new OrderStatusHistory(this, null, OrderStatus.CREATED, null));
    }

    public static Order create(String customerId, BigDecimal amount, String currency) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (amount.scale() > AMOUNT_SCALE) {
            throw new IllegalArgumentException("amount must have at most two decimals");
        }
        return new Order(customerId, amount.setScale(AMOUNT_SCALE), currency);
    }

    public void markProcessing() {
        transition(OrderStatus.PROCESSING, null);
    }

    public void markProcessed() {
        transition(OrderStatus.PROCESSED, null);
    }

    public void markFailed(String reason) {
        transition(OrderStatus.FAILED, reason);
    }

    private void transition(OrderStatus target, String reason) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStatusTransitionException(id, status, target);
        }
        history.add(new OrderStatusHistory(this, status, target, reason));
        status = target;
    }

    public Long getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public List<OrderStatusHistory> getHistory() {
        return Collections.unmodifiableList(history);
    }
}
