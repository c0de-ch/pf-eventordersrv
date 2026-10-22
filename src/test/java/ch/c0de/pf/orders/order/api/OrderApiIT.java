package ch.c0de.pf.orders.order.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.c0de.pf.orders.TestcontainersConfiguration;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderApiIT {

    @Autowired
    MockMvcTester mvc;

    @Test
    void createsReadsAndListsOrder() throws Exception {
        String customerId = "c-" + UUID.randomUUID().toString().substring(0, 8);

        MvcTestResult created = mvc.post().uri("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"customerId\": \"%s\", \"amount\": 99.9}".formatted(customerId))
            .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        String location = Objects.requireNonNull(created.getResponse().getHeader("Location"), "Location header");
        assertThat(location).matches("http://localhost/orders/\\d+");

        MvcTestResult read = mvc.get().uri(location).exchange();
        assertThat(read).hasStatusOk();
        assertThat(read).bodyJson().isLenientlyEqualTo(created.getResponse().getContentAsString());
        assertThat(read).bodyJson().extractingPath("$.status").asString().isIn("CREATED", "PROCESSING", "PROCESSED");
        assertThat(read).bodyJson().extractingPath("$.currency").isEqualTo("CHF");
        assertThat(read).bodyJson().extractingPath("$.amount").isEqualTo(99.9);
        assertThat(read).bodyJson().extractingPath("$.createdAt").isNotNull();
        assertThat(read).bodyJson().extractingPath("$.history[0].to").isEqualTo("CREATED");
        assertThat(read).bodyJson().extractingPath("$.history[0].at").isNotNull();

        MvcTestResult listed = mvc.get().uri("/orders?customerId={id}", customerId).exchange();
        assertThat(listed).hasStatusOk();
        assertThat(listed).bodyJson().extractingPath("$.page.totalElements").isEqualTo(1);
        assertThat(listed).bodyJson().extractingPath("$.content[0].customerId").isEqualTo(customerId);
    }

    @Test
    void publishesOpenApiDocument() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.paths['/orders'].post").isNotNull();
        assertThat(result).bodyJson().extractingPath("$.paths['/orders/{id}'].get").isNotNull();
    }
}
