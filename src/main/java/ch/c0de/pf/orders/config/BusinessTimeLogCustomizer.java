package ch.c0de.pf.orders.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.core.env.Environment;

// Log timestamps stay real (log stores reject shifted ones); app.time carries the shifted business clock
public class BusinessTimeLogCustomizer implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

    static final String FIELD = "app.time";

    private final Duration offset;

    public BusinessTimeLogCustomizer(Environment environment) {
        this.offset = Duration.parse(environment.getProperty("orders.clock.offset", "PT0S"));
    }

    @Override
    public void customize(JsonWriter.Members<ILoggingEvent> members) {
        members.add(FIELD, (ILoggingEvent event) -> businessTime(event.getTimeStamp()).toString());
    }

    Instant businessTime(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).plus(offset);
    }
}
