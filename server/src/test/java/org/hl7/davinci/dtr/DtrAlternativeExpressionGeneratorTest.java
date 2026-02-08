package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.cqframework.cql.cql2elm.CqlCompilerOptions;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.LibraryManager;
import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.cqframework.cql.cql2elm.ModelManager;
import org.hl7.elm.r1.VersionedIdentifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kotlinx.io.Buffer;
import kotlinx.io.Source;

/**
 * Compiles the HospitalBedsAndAccessoriesPrepopulation CQL to ELM JSON and
 * verifies that the three prepopulation expressions produce valid ELM.
 *
 * Run this test to regenerate alternativeExpression ELM fragments for the
 * Questionnaire. The full ELM JSON is output to stdout for reference.
 */
class DtrAlternativeExpressionGeneratorTest {

  private static final String PREPOP_CQL_PATH =
      "library/HospitalBedsAndAccessories/HospitalBedsAndAccessoriesPrepopulation.cql";
  private static final String DTR_HELPERS_CQL_PATH =
      "library/dtr/DTRHelpers.cql";

  /**
   * Source provider that reads CQL from classpath (library/ directory).
   * Resolves DTRHelpers; FHIRHelpers is resolved by the built-in provider.
   */
  private static class ClasspathLibrarySourceProvider implements LibrarySourceProvider {
    @Override
    public Source getLibrarySource(VersionedIdentifier libraryIdentifier) {
      String name = libraryIdentifier.getId();
      if ("FHIRHelpers".equals(name)) {
        return null; // Let built-in provider handle it
      }
      if ("DTRHelpers".equals(name)) {
        return loadFromClasspath("library/dtr/DTRHelpers.cql");
      }
      return null;
    }

    private Source loadFromClasspath(String path) {
      InputStream is = Thread.currentThread().getContextClassLoader()
          .getResourceAsStream(path);
      if (is == null) {
        return null;
      }
      try {
        byte[] bytes = is.readAllBytes();
        Buffer buffer = new Buffer();
        buffer.write(bytes, 0, bytes.length);
        return buffer;
      } catch (Exception e) {
        return null;
      }
    }
  }

  @Test
  @DisplayName("Compile prepopulation CQL to ELM JSON")
  void compilePrepopulationCql() throws Exception {
    String cqlText = loadResource(PREPOP_CQL_PATH);
    assertNotNull(cqlText, "CQL file should be on classpath");

    ModelManager modelManager = new ModelManager();
    CqlCompilerOptions options = CqlCompilerOptions.defaultOptions();
    LibraryManager libraryManager = new LibraryManager(modelManager, options);
    libraryManager.getLibrarySourceLoader().registerProvider(new ClasspathLibrarySourceProvider());

    CqlTranslator translator = CqlTranslator.fromText(cqlText, libraryManager);

    if (!translator.getErrors().isEmpty()) {
      String errors = translator.getErrors().stream()
          .map(e -> e.getMessage())
          .reduce((a, b) -> a + "\n" + b)
          .orElse("Unknown");
      fail("CQL compilation failed:\n" + errors);
    }

    String elmJson = translator.toJson();
    assertNotNull(elmJson);
    assertFalse(elmJson.isEmpty());

    // Verify the three key expressions are in the ELM
    assertTrue(elmJson.contains("\"BodyWeight\""), "ELM should contain BodyWeight expression");
    assertTrue(elmJson.contains("\"EncounterList\""), "ELM should contain EncounterList expression");
    assertTrue(elmJson.contains("\"DeviceRequested\""), "ELM should contain DeviceRequested expression");

    // Write ELM to temp file for inspection
    java.nio.file.Path elmFile = java.nio.file.Path.of(
        System.getProperty("project.build.directory", "target"), "prepopulation-elm.json");
    java.nio.file.Files.writeString(elmFile, elmJson);
    System.out.println("ELM JSON written to: " + elmFile.toAbsolutePath());
  }

  @Test
  @DisplayName("Compile DTRHelpers CQL to ELM JSON")
  void compileDtrHelpersCql() throws Exception {
    String cqlText = loadResource(DTR_HELPERS_CQL_PATH);
    assertNotNull(cqlText, "DTRHelpers CQL should be on classpath");

    ModelManager modelManager = new ModelManager();
    CqlCompilerOptions options = CqlCompilerOptions.defaultOptions();
    LibraryManager libraryManager = new LibraryManager(modelManager, options);

    CqlTranslator translator = CqlTranslator.fromText(cqlText, libraryManager);

    if (!translator.getErrors().isEmpty()) {
      String errors = translator.getErrors().stream()
          .map(e -> e.getMessage())
          .reduce((a, b) -> a + "\n" + b)
          .orElse("Unknown");
      fail("DTRHelpers CQL compilation failed:\n" + errors);
    }

    String elmJson = translator.toJson();
    assertNotNull(elmJson);
    assertFalse(elmJson.isEmpty());

    // Key functions should be present
    assertTrue(elmJson.contains("\"HighestObservation\""), "ELM should contain HighestObservation");
    assertTrue(elmJson.contains("\"Verified\""), "ELM should contain Verified");
    assertTrue(elmJson.contains("\"WithUnit\""), "ELM should contain WithUnit");
    assertTrue(elmJson.contains("\"ObservationLookBack\""), "ELM should contain ObservationLookBack");
    assertTrue(elmJson.contains("\"PeriodToInterval\""), "ELM should contain PeriodToInterval");
  }

  private String loadResource(String path) throws Exception {
    InputStream is = Thread.currentThread().getContextClassLoader()
        .getResourceAsStream(path);
    if (is == null) return null;
    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
  }
}
