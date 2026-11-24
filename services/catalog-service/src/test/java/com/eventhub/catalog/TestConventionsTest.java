package com.eventhub.catalog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

/** The testing rules of CLAUDE.md, checked on the test classes themselves. */
@AnalyzeClasses(
    packages = "com.eventhub.catalog",
    importOptions = ImportOption.OnlyIncludeTests.class)
class TestConventionsTest {

  @ArchTest
  static final ArchRule test_names_state_the_result_and_the_condition =
      methods()
          .that()
          .areAnnotatedWith(Test.class)
          .or()
          .areAnnotatedWith(ParameterizedTest.class)
          .should()
          .haveNameMatching("should_.+_when_.+")
          .because("CLAUDE.md: should_<resultado>_when_<condicion>");

  @ArchTest
  static final ArchRule tests_never_sleep =
      noClasses()
          .should()
          .callMethod(Thread.class, "sleep", long.class)
          .orShould()
          .callMethod(Thread.class, "sleep", Duration.class)
          .because("CLAUDE.md: use Awaitility instead of Thread.sleep");
}
