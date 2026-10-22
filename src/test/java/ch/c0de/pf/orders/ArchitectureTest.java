package ch.c0de.pf.orders;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import ch.c0de.pf.orders.common.AuditedEntity;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Date;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(packages = ArchitectureTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String ROOT = "ch.c0de.pf.orders";

    @ArchTest
    static final ArchRule domainDoesNotDependOnApi = noClasses().that().resideInAPackage(ROOT + ".order")
        .should().dependOnClassesThat().resideInAPackage(ROOT + ".order.api..");

    @ArchTest
    static final ArchRule commonIsIndependent = noClasses().that().resideInAPackage(ROOT + ".common..")
        .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".order..", ROOT + ".outbox..", ROOT + ".messaging..", ROOT + ".config..");

    @ArchTest
    static final ArchRule outboxKnowsNoDomain = noClasses().that().resideInAPackage(ROOT + ".outbox..")
        .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".order..", ROOT + ".messaging..", ROOT + ".config..");

    @ArchTest
    static final ArchRule domainDoesNotDependOnMessaging = noClasses().that().resideInAPackage(ROOT + ".order..")
        .should().dependOnClassesThat().resideInAPackage(ROOT + ".messaging..");

    @ArchTest
    static final ArchRule controllersLiveInApiPackages = classes().that().areAnnotatedWith(RestController.class)
        .should().resideInAPackage(ROOT + "..api..");

    @ArchTest
    static final ArchRule entitiesAreAudited = classes().that().areAnnotatedWith(Entity.class)
        .should().beAssignableTo(AuditedEntity.class);

    @ArchTest
    static final ArchRule businessTimeComesFromTheClock = noClasses()
        .should().callMethod(Instant.class, "now")
        .orShould().callMethod(LocalDate.class, "now")
        .orShould().callMethod(LocalDateTime.class, "now")
        .orShould().callMethod(OffsetDateTime.class, "now")
        .orShould().callMethod(ZonedDateTime.class, "now")
        .orShould().callMethod(System.class, "currentTimeMillis")
        .orShould().callConstructor(Date.class)
        .because("business time must come from the Clock bean");

    @ArchTest
    static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
}
