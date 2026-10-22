package ch.c0de.pf.orders;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.activemq.ArtemisContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@TestConfiguration(proxyBeanMethods = false)
public class ArtemisContainerConfiguration {

    // Same broker policy as compose: redelivery, max attempts and the dead-letter queue
    @Bean
    @ServiceConnection
    ArtemisContainer artemisContainer() {
        return new ArtemisContainer(DockerImageName.parse("apache/artemis:2.57.0-alpine"))
            .withCopyFileToContainer(MountableFile.forHostPath("deploy/artemis/broker.xml"),
                "/var/lib/artemis-instance/etc-override/broker.xml");
    }
}
