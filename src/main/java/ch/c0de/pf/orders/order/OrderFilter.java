package ch.c0de.pf.orders.order;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public record OrderFilter(String customerId, OrderStatus status) {

    Specification<Order> toSpecification() {
        List<Specification<Order>> conditions = new ArrayList<>();
        if (customerId != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("customerId"), customerId));
        }
        if (status != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return Specification.allOf(conditions);
    }
}
