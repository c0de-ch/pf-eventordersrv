package ch.c0de.pf.orders.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Component
public class Outbox {

    private final OutboxRepository repository;
    private final JsonMapper json;

    public Outbox(OutboxRepository repository, JsonMapper json) {
        this.repository = repository;
        this.json = json;
    }

    // MANDATORY: an outbox row written outside the caller's transaction would defeat the pattern
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent append(String aggregateType, Long aggregateId, String eventType, Object payload) {
        return repository.save(OutboxEvent.of(aggregateType, aggregateId, eventType, json.writeValueAsString(payload)));
    }
}
