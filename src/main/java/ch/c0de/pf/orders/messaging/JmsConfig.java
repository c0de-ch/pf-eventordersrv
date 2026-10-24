package ch.c0de.pf.orders.messaging;

import ch.c0de.pf.orders.order.OrderAcceptedEvent;
import io.micrometer.observation.ObservationRegistry;
import jakarta.jms.ConnectionFactory;
import java.util.Map;
import org.springframework.boot.jms.autoconfigure.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.support.converter.JacksonJsonMessageConverter;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class JmsConfig {

    // Text messages with a "_type" property, mapped to event classes; the publisher sets the same property
    @Bean
    MessageConverter jmsMessageConverter(JsonMapper jsonMapper) {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter(jsonMapper);
        converter.setTargetType(MessageType.TEXT);
        converter.setTypeIdPropertyName(JmsOutboxPublisher.TYPE_PROPERTY);
        converter.setTypeIdMappings(Map.of(OrderAcceptedEvent.TYPE, OrderAcceptedEvent.class));
        return converter;
    }

    // Same factory Boot would build, plus a one-line error handler instead of a stack trace per redelivery
    @Bean
    DefaultJmsListenerContainerFactory jmsListenerContainerFactory(DefaultJmsListenerContainerFactoryConfigurer configurer,
                                                                   ConnectionFactory connectionFactory,
                                                                   ObservationRegistry observations) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setErrorHandler(new ListenerErrorHandler(observations));
        return factory;
    }
}
