package ch.c0de.pf.orders.order;

import java.util.EnumSet;
import java.util.Set;

public enum OrderStatus {
    CREATED, PROCESSING, PROCESSED, FAILED;

    public Set<OrderStatus> successors() {
        return switch (this) {
            // CREATED -> FAILED covers messages dead-lettered before processing started
            case CREATED -> EnumSet.of(PROCESSING, FAILED);
            case PROCESSING -> EnumSet.of(PROCESSED, FAILED);
            case PROCESSED, FAILED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus target) {
        return successors().contains(target);
    }

    public boolean isFinal() {
        return successors().isEmpty();
    }
}
