package ch.c0de.pf.orders;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

@TestConfiguration(proxyBeanMethods = false)
@Import({PostgresContainerConfiguration.class, ArtemisContainerConfiguration.class})
public class TestcontainersConfiguration {
}
