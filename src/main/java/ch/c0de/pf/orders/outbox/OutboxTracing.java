package ch.c0de.pf.orders.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OutboxTracing {

    static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    // W3C traceparent of the current span, stored with the event so the relay can continue the trace later
    String currentTraceContext() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    void publishWithinTrace(OutboxEvent event, Runnable publish) {
        if (event.getTraceContext() == null) {
            publish.run();
            return;
        }
        Span span = propagator.extract(Map.of(TRACEPARENT, event.getTraceContext()), Map::get)
            .name("outbox publish")
            .tag("outbox.event_id", String.valueOf(event.getId()))
            .tag("outbox.aggregate_id", String.valueOf(event.getAggregateId()))
            .start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            publish.run();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
