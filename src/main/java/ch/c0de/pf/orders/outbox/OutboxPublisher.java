package ch.c0de.pf.orders.outbox;

public interface OutboxPublisher {

    void publish(OutboxEvent event);
}
