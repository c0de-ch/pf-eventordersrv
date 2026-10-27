package ch.c0de.pf.orders.order.processing;

import static org.assertj.core.api.Assertions.assertThatCode;

import ch.c0de.pf.orders.order.OrderTestData;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

// Class proxies as in the app: a JDK interface proxy hides the annotation from the retry interceptor
@ImportAutoConfiguration(AopAutoConfiguration.class)
@SpringJUnitConfig({ProcessingConfig.class, SimulatedOrderProcessor.class, SimulatedOrderProcessorRetryTest.Properties.class})
class SimulatedOrderProcessorRetryTest {

    @Autowired
    OrderProcessor processor;

    @Test
    void retriesTransientFailureWithinTheSameCall() {
        assertThatCode(() -> processor.process(OrderTestData.persistedOrder(1L, "FLAKY-1"))).doesNotThrowAnyException();
    }

    @Configuration(proxyBeanMethods = false)
    static class Properties {

        @Bean
        ProcessingProperties processingProperties() {
            return new ProcessingProperties(Duration.ZERO, "FAIL-", "FLAKY-");
        }
    }
}
