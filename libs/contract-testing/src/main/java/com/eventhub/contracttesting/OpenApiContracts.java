package com.eventhub.contracttesting;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport.Level;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the OpenAPI conformance validator for a contract in {@code contracts/openapi}, read in
 * place and never copied into a module.
 *
 * <p>The error cases of a test send deliberately malformed requests, so the validator judges
 * responses only: request body, parameter and security findings are ignored, while an unknown path
 * or method is still an error.
 */
public final class OpenApiContracts {

  private static final Path DIRECTORY =
      Path.of("../../contracts/openapi").toAbsolutePath().normalize();

  private OpenApiContracts() {}

  public static OpenApiInteractionValidator validatorFor(String fileName) {
    Path spec = DIRECTORY.resolve(fileName);
    if (!Files.isRegularFile(spec)) {
      throw new IllegalArgumentException("No OpenAPI contract " + fileName + " in " + DIRECTORY);
    }
    return OpenApiInteractionValidator.createFor(spec.toUri().toString())
        .withLevelResolver(
            LevelResolver.create()
                .withLevel("validation.request.body", Level.IGNORE)
                .withLevel("validation.request.parameter", Level.IGNORE)
                .withLevel("validation.request.security", Level.IGNORE)
                .build())
        .build();
  }
}
