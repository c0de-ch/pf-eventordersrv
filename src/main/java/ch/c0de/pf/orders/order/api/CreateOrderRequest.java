package ch.c0de.pf.orders.order.api;

import ch.c0de.pf.orders.order.CreateOrder;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateOrderRequest(
    @NotBlank @Size(max = 64) String customerId,
    @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
    @Pattern(regexp = "[A-Z]{3}") String currency) {

    CreateOrder toCommand() {
        return new CreateOrder(customerId, amount, currency);
    }
}
