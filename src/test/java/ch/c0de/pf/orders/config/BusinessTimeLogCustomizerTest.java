package ch.c0de.pf.orders.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BusinessTimeLogCustomizerTest {

    @Test
    void shiftsLogTimestampByConfiguredOffset() {
        BusinessTimeLogCustomizer customizer = new BusinessTimeLogCustomizer(
            new MockEnvironment().withProperty("orders.clock.offset", "P30D"));

        assertThat(customizer.businessTime(0)).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(30)));
    }

    @Test
    void keepsRealTimeWithoutOffset() {
        assertThat(new BusinessTimeLogCustomizer(new MockEnvironment()).businessTime(1_000)).isEqualTo(Instant.ofEpochMilli(1_000));
    }
}
