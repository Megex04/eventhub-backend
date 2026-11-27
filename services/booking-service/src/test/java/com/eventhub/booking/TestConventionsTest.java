package com.eventhub.booking;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.time.Duration;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

/**
 * The testing rules of CLAUDE.md, checked on the test classes themselves. The naming rule is
 * frozen: the tests written before it existed that lack the {@code _when_} part are recorded in
 * {@code archunit_store} (known debt, they are renamed when touched), and any new test that breaks
 * it fails the build. Catalog-service has no such debt and checks the rule strictly.
 */
@AnalyzeClasses(
    packages = "com.eventhub.booking",
    importOptions = ImportOption.OnlyIncludeTests.class)
class TestConventionsTest {

  @ArchTest
  static final ArchRule test_names_state_the_result_and_the_condition =
      FreezingArchRule.freeze(
          methods()
              .that()
              .areAnnotatedWith(Test.class)
              .or()
              .areAnnotatedWith(ParameterizedTest.class)
              .or()
              .areAnnotatedWith(RepeatedTest.class)
              .should()
              .haveNameMatching("should_.+_when_.+")
              .because("CLAUDE.md: should_<resultado>_when_<condicion>"));

  @ArchTest
  static final ArchRule tests_never_sleep =
      noClasses()
          .should()
          .callMethod(Thread.class, "sleep", long.class)
          .orShould()
          .callMethod(Thread.class, "sleep", Duration.class)
          .because("CLAUDE.md: use Awaitility instead of Thread.sleep");
}
