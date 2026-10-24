package ch.c0de.pf.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// The event.action words are a published contract (docs/operations.md): code and documentation must not drift apart
class LoggingContractTest {

    static final Set<String> VOCABULARY = Set.of(
        "order.accepted", "order.event.published", "order.event.publish_failed", "order.event.received",
        "order.processing.started", "order.processing.resumed", "order.processing.skipped", "order.processed",
        "order.failed", "order.failure.ignored", "order.event.dead_lettered");

    private static final Pattern EVENT_ACTION = Pattern.compile("addKeyValue\\(\"event\\.action\",\\s*\"([^\"]+)\"\\)");

    @Test
    void codeUsesExactlyTheDocumentedVocabulary() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            Set<String> used = files.filter(path -> path.toString().endsWith(".java"))
                .flatMap(LoggingContractTest::eventActions)
                .collect(Collectors.toSet());
            assertThat(used).containsExactlyInAnyOrderElementsOf(VOCABULARY);
        }
    }

    private static Stream<String> eventActions(Path file) {
        try {
            return EVENT_ACTION.matcher(Files.readString(file)).results().map(result -> result.group(1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
