package ch.c0de.pf.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.c0de.pf.orders.outbox.OutboxRepository;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    OutboxRepository outbox;

    @Autowired
    JsonMapper json;

    @Test
    void createdOrderIsProcessedAsynchronously() {
        long orderId = createOrder("c-" + suffix());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(order(orderId).get("status").asString()).isEqualTo("PROCESSED"));

        assertThat(statusHistory(orderId)).containsExactly("CREATED", "PROCESSING", "PROCESSED");
        assertThat(outbox.findAll().stream().filter(e -> Objects.equals(e.getAggregateId(), orderId)))
            .singleElement().satisfies(event -> assertThat(event.isPublished()).isTrue());
    }

    @Test
    void failingOrderIsDeadLetteredAndMarkedFailed() {
        long orderId = createOrder("FAIL-" + suffix());

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
            assertThat(order(orderId).get("status").asString()).isEqualTo("FAILED"));

        assertThat(statusHistory(orderId)).containsExactly("CREATED", "PROCESSING", "FAILED");
        assertThat(order(orderId).get("history").get(2).get("reason").asString()).contains("dead-lettered");
    }

    @Test
    void flakyOrderRecoversThroughInProcessRetry() {
        long orderId = createOrder("FLAKY-" + suffix());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(order(orderId).get("status").asString()).isEqualTo("PROCESSED"));

        assertThat(statusHistory(orderId)).containsExactly("CREATED", "PROCESSING", "PROCESSED");
    }

    private long createOrder(String customerId) {
        MvcTestResult created = mvc.post().uri("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"customerId\": \"%s\", \"amount\": 99.9}".formatted(customerId))
            .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return body(created).get("id").asLong();
    }

    private JsonNode order(long orderId) {
        MvcTestResult result = mvc.get().uri("/orders/{id}", orderId).exchange();
        assertThat(result).hasStatusOk();
        return body(result);
    }

    private List<String> statusHistory(long orderId) {
        return order(orderId).get("history").valueStream().map(entry -> entry.get("to").asString()).toList();
    }

    private JsonNode body(MvcTestResult result) {
        try {
            return json.readTree(result.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
