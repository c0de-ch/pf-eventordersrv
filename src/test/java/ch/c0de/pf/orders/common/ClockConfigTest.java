package ch.c0de.pf.orders.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

    @Test
    void usesSystemTimeWithoutOffset() {
        Clock clock = new ClockConfig().clock(new ClockProperties(Duration.ZERO));

        assertThat(clock.instant()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void ticksInMicrosecondsLikeTheDatabase() {
        Clock clock = new ClockConfig().clock(new ClockProperties(Duration.ZERO));

        assertThat(clock.instant().getNano() % 1_000).isZero();
    }

    @Test
    void shiftsTimeByConfiguredOffset() {
        Duration offset = Duration.ofDays(30);

        Clock clock = new ClockConfig().clock(new ClockProperties(offset));

        assertThat(clock.instant()).isCloseTo(Instant.now().plus(offset), within(5, ChronoUnit.SECONDS));
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
