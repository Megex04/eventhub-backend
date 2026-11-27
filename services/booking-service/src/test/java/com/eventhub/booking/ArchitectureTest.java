package com.eventhub.booking;

import static com.tngtech.archunit.lang.SimpleConditionEvent.satisfied;
import static com.tngtech.archunit.lang.SimpleConditionEvent.violated;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.library.GeneralCodingRules;
import jakarta.persistence.Entity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rules of CLAUDE.md ("Arquitectura hexagonal", "Modelado de dominio", "Estilo y calidad"). A
 * violation breaks the build. Generated code (contract-first interfaces and DTOs) is part of the
 * analysed classes on purpose: it is what the controllers must implement.
 */
@AnalyzeClasses(
    packages = "com.eventhub.booking",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  private static final String DOMAIN = "com.eventhub.booking.domain..";
  private static final String APPLICATION = "com.eventhub.booking.application..";
  private static final String INFRASTRUCTURE = "com.eventhub.booking.infrastructure..";

  // ---- layers ----

  @ArchTest
  static final ArchRule layers_only_depend_inwards =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("Domain")
          .definedBy(DOMAIN)
          .layer("Application")
          .definedBy(APPLICATION)
          .layer("Infrastructure")
          .definedBy(INFRASTRUCTURE)
          .whereLayer("Infrastructure")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Application")
          .mayOnlyBeAccessedByLayers("Infrastructure")
          .whereLayer("Domain")
          .mayOnlyBeAccessedByLayers("Application", "Infrastructure");

  @ArchTest
  static final ArchRule domain_is_plain_java =
      classes()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("com.eventhub.booking.domain..", "java..");

  /**
   * Logging context (MDC), web, persistence and JSON are adapter concerns reached through ports.
   */
  @ArchTest
  static final ArchRule application_uses_no_logging_web_persistence_or_json =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.slf4j..",
              "ch.qos.logback..",
              "jakarta.persistence..",
              "jakarta.servlet..",
              "org.hibernate..",
              "org.springframework.web..",
              "org.springframework.http..",
              "org.springframework.data..",
              "org.springframework.jdbc..",
              "org.springframework.orm..",
              "org.springframework.security..",
              "tools.jackson..",
              "com.fasterxml..");

  @ArchTest
  static final ArchRule use_case_implementations_only_know_the_domain_and_their_ports =
      classes()
          .that()
          .resideInAPackage("..application.service..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage(
              "com.eventhub.booking.domain..",
              "com.eventhub.booking.application..",
              "java..",
              "org.springframework.stereotype..",
              "org.springframework.transaction.annotation..");

  @ArchTest
  static final ArchRule ports_are_interfaces_or_records =
      classes()
          .that()
          .resideInAPackage("..application.port..")
          .should()
          .beInterfaces()
          .orShould()
          .beAssignableTo(Record.class)
          .orShould()
          .beAssignableTo(Throwable.class);

  // ---- adapters ----

  @ArchTest
  static final ArchRule controllers_use_ports_and_generated_interfaces_not_other_adapters =
      noClasses()
          .that()
          .resideInAPackage("..adapter.in.web..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..adapter.out..",
              "jakarta.persistence..",
              "org.springframework.data..",
              "org.springframework.jdbc..");

  @ArchTest
  static final ArchRule controllers_implement_a_generated_contract_interface =
      classes()
          .that()
          .areAnnotatedWith(RestController.class)
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..adapter.in.web.api..");

  @ArchTest
  static final ArchRule jpa_entities_live_only_in_the_persistence_adapter =
      classes()
          .that()
          .areAnnotatedWith(Entity.class)
          .should()
          .resideInAPackage("..adapter.out.persistence..");

  @ArchTest
  static final ArchRule nothing_but_persistence_uses_jpa_entities =
      noClasses()
          .that()
          .resideOutsideOfPackage("..adapter.out.persistence..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..adapter.out.persistence..")
          .because("the domain model and the use cases never see persistence classes");

  // ---- modelling ----

  @ArchTest
  static final ArchRule aggregates_have_no_public_setters =
      noMethods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("..domain..")
          .and()
          .arePublic()
          .should()
          .haveNameMatching("set[A-Z].*")
          .because("changes go through intention-revealing methods (publish, cancel...)");

  @ArchTest
  static final ArchRule time_comes_from_an_injected_clock_in_domain_and_application =
      noClasses()
          .that()
          .resideInAnyPackage("..domain..", "..application..")
          .should()
          .callMethod(Instant.class, "now")
          .orShould()
          .callMethod(LocalDate.class, "now")
          .orShould()
          .callMethod(LocalDateTime.class, "now")
          .orShould()
          .callMethod(OffsetDateTime.class, "now")
          .orShould()
          .callMethod(ZonedDateTime.class, "now")
          .orShould()
          .callMethod(System.class, "currentTimeMillis");

  // ---- style ----

  @ArchTest
  static final ArchRule no_field_injection = noFields().should().beAnnotatedWith(Autowired.class);

  @ArchTest
  static final ArchRule no_standard_streams =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  @ArchTest
  static final ArchRule no_java_util_logging =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

  @ArchTest
  static final ArchRule no_generic_exceptions_are_thrown =
      GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

  /** {@code catch (Exception e)} and {@code catch (Throwable t)} hide what can really fail. */
  @ArchTest
  static final ArchRule no_generic_catch =
      methods()
          .that(areNotSynthetic())
          .should(notCatchExceptionOrThrowable())
          .because("catch the specific failures; an @ExceptionHandler is the place for the rest");

  // ---- extra rules (B8) ----

  /** The application layer knows Spring only through {@code @Service}/{@code @Transactional}. */
  @ArchTest
  static final ArchRule application_has_no_spring_beyond_stereotype_and_transactional =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat(
              JavaClass.Predicates.resideInAPackage("org.springframework..")
                  .and(
                      DescribedPredicate.not(
                          JavaClass.Predicates.resideInAnyPackage(
                              "org.springframework.stereotype..",
                              "org.springframework.transaction.annotation.."))))
          .because("CLAUDE.md: application depends only on domain and its own ports");

  /**
   * Correlation travels through a port ({@code CorrelationIdProvider}); MDC is an adapter detail.
   */
  @ArchTest
  static final ArchRule application_never_touches_the_logging_context =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.slf4j.MDC");

  /** Not only domain and application: nothing in the service reads the system clock. */
  @ArchTest
  static final ArchRule nothing_reads_the_system_clock =
      noClasses()
          .should()
          .callMethod(Instant.class, "now")
          .orShould()
          .callMethod(LocalDate.class, "now")
          .orShould()
          .callMethod(LocalDateTime.class, "now")
          .orShould()
          .callMethod(OffsetDateTime.class, "now")
          .orShould()
          .callMethod(ZonedDateTime.class, "now")
          .orShould()
          .callMethod(System.class, "currentTimeMillis")
          .because("CLAUDE.md: inject java.time.Clock");

  // ---- payment gateway (booking-service) ----

  /**
   * The simulated gateway is the only implementation, lives in its adapter package, off in
   * production.
   */
  @ArchTest
  static final ArchRule gateway_implementations_live_in_the_payment_adapter_under_a_profile =
      classes()
          .that()
          .implement(com.eventhub.booking.application.port.out.PaymentGateway.class)
          .should()
          .resideInAPackage("..infrastructure.adapter.out.payment..")
          .andShould()
          .beAnnotatedWith(org.springframework.context.annotation.Profile.class)
          .because(
              "CLAUDE.md: a fake gateway only in local/dev/test; production without one must fail");

  @ArchTest
  static final ArchRule only_the_payment_adapter_and_its_config_know_the_simulated_gateway =
      noClasses()
          .that()
          .resideOutsideOfPackages("..infrastructure.adapter.out.payment..")
          .should()
          .dependOnClassesThat()
          .haveSimpleNameStartingWith("SimulatedPaymentGateway")
          .because("everything else goes through the PaymentGateway port");

  private static DescribedPredicate<JavaMethod> areNotSynthetic() {
    return DescribedPredicate.describe(
        "are not synthetic", method -> !method.getModifiers().contains(JavaModifier.SYNTHETIC));
  }

  private static ArchCondition<JavaMethod> notCatchExceptionOrThrowable() {
    return new ArchCondition<>("not catch Exception or Throwable") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        method.getTryCatchBlocks().stream()
            .flatMap(block -> block.getCaughtThrowables().stream())
            .filter(ArchitectureTest::isGeneric)
            .forEach(caught ->
                events.add(
                    violated(
                        method,
                        "%s catches %s in %s"
                            .formatted(
                                method.getFullName(),
                                caught.getName(),
                                method.getSourceCodeLocation()))));
        if (method.getTryCatchBlocks().isEmpty()) {
          events.add(satisfied(method, method.getFullName() + " has no try/catch"));
        }
      }
    };
  }

  private static boolean isGeneric(JavaClass caught) {
    return caught.isEquivalentTo(Exception.class) || caught.isEquivalentTo(Throwable.class);
  }
}
