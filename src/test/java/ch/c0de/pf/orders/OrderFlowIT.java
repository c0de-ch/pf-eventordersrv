package ch.c0de.pf.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.c0de.pf.orders.outbox.OutboxEvent;
import ch.c0de.pf.orders.outbox.OutboxRepository;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderFlowIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ConnectionFactory connectionFactory;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    JsonMapper json;

    @Test
    void createdOrderIsPublishedToTheQueue() throws Exception {
        String customerId = "c-" + UUID.randomUUID().toString().substring(0, 8);
        MvcTestResult created = mvc.post().uri("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"customerId\": \"%s\", \"amount\": 99.9}".formatted(customerId))
            .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        long orderId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        JmsTemplate receiver = new JmsTemplate(connectionFactory);
        receiver.setReceiveTimeout(Duration.ofSeconds(15).toMillis());
        Message message = receiver.receiveSelected("order.created", "JMSCorrelationID = '%d'".formatted(orderId));

        assertThat(message).isInstanceOf(TextMessage.class);
        assertThat(message.getStringProperty("_type")).isEqualTo("OrderCreated");
        assertThat(message.getLongProperty("eventId")).isPositive();
        JsonNode body = json.readTree(((TextMessage) message).getText());
        assertThat(body.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(body.get("customerId").asString()).isEqualTo(customerId);
        assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("99.90");
        assertThat(body.get("currency").asString()).isEqualTo("CHF");
        assertThat(body.get("occurredAt").asString()).isNotBlank();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            OutboxEvent event = outbox.findAll().stream()
                .filter(e -> Objects.equals(e.getAggregateId(), orderId)).findFirst().orElseThrow();
            assertThat(event.isPublished()).isTrue();
            assertThat(event.getAttempts()).isEqualTo(1);
        });
    }
}
