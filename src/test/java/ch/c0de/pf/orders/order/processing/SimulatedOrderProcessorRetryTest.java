package ch.c0de.pf.orders.order.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.c0de.pf.orders.order.Order;
import ch.c0de.pf.orders.order.OrderTestData;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.util.ReflectionUtils;

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

    // FLAKY- fails only once, so the attempt count and the delay cannot be observed; pin the declared policy instead
    @Test
    void retriesTransientFailuresTwiceAfter200Milliseconds() {
        Retryable retry = AnnotatedElementUtils.findMergedAnnotation(
            ReflectionUtils.findMethod(SimulatedOrderProcessor.class, "process", Order.class), Retryable.class);

        assertThat(retry).isNotNull();
        assertThat(retry.includes()).containsExactly(TransientProcessingException.class);
        assertThat(retry.maxRetries()).isEqualTo(2);
        assertThat(retry.delay()).isEqualTo(200);
        assertThat(retry.timeUnit()).isEqualTo(TimeUnit.MILLISECONDS);
    }

    @Configuration(proxyBeanMethods = false)
    static class Properties {

        @Bean
        ProcessingProperties processingProperties() {
            return new ProcessingProperties(Duration.ZERO, "FAIL-", "FLAKY-");
        }
    }
}
