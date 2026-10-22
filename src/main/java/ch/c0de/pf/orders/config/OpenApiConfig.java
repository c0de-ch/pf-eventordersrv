package ch.c0de.pf.orders.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(title = "Order Service API", version = "v1",
    description = "Create orders and follow their asynchronous processing."))
class OpenApiConfig {
}
