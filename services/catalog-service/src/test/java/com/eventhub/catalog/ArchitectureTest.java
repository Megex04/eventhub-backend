package com.eventhub.catalog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;

/** The dependency rules of CLAUDE.md ("Arquitectura hexagonal"); a violation breaks the build. */
@AnalyzeClasses(
    packages = "com.eventhub.catalog",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule domain_is_plain_java =
      classes()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("com.eventhub.catalog.domain..", "java..");

  @ArchTest
  static final ArchRule application_does_not_depend_on_infrastructure =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..infrastructure..");

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
  static final ArchRule domain_does_not_depend_on_application =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..application..");

  @ArchTest
  static final ArchRule no_field_injection = noFields().should().beAnnotatedWith(Autowired.class);
}
