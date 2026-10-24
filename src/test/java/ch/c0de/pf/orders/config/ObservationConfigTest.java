package ch.c0de.pf.orders.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ObservationConfigTest {

    private final ObservationPredicate predicate = new ObservationConfig().noActuatorServerObservations();

    @Test
    void dropsActuatorRequests() {
        assertThat(predicate.test("http.server.requests", serverRequest("/actuator/health/liveness"))).isFalse();
        assertThat(predicate.test("http.server.requests", serverRequest("/actuator/prometheus"))).isFalse();
    }

    @Test
    void keepsApiRequestsAndOtherObservations() {
        assertThat(predicate.test("http.server.requests", serverRequest("/orders"))).isTrue();
        assertThat(predicate.test("order.processing", new Observation.Context())).isTrue();
    }

    private static ServerRequestObservationContext serverRequest(String uri) {
        return new ServerRequestObservationContext(new MockHttpServletRequest("GET", uri), new MockHttpServletResponse());
    }
}
