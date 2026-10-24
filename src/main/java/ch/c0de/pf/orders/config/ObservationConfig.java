package ch.c0de.pf.orders.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration(proxyBeanMethods = false)
class ObservationConfig {

    // Probes and scrapes hit /actuator every few seconds; a trace and a timer per call is cost without a reader
    @Bean
    ObservationPredicate noActuatorServerObservations() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
            && server.getCarrier().getRequestURI().startsWith("/actuator"));
    }
}
