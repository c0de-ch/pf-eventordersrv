package ch.c0de.pf.orders;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@AutoConfigureTracing
@Import(TestcontainersConfiguration.class)
class OrderServiceApplicationIT {

    @Autowired
    MockMvcTester mvc;

    @Test
    void healthIsUpWithDatabaseAndBroker() {
        MvcTestResult result = mvc.get().uri("/actuator/health").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("UP");
        assertThat(result).bodyJson().extractingPath("$.components.db.status").isEqualTo("UP");
        assertThat(result).bodyJson().extractingPath("$.components.jms.status").isEqualTo("UP");
    }

    @Test
    void readinessDependsOnDatabaseOnly() {
        MvcTestResult result = mvc.get().uri("/actuator/health/readiness").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.components.db.status").isEqualTo("UP");
        assertThat(result).bodyJson().doesNotHavePath("$.components.jms");
    }
}
