package org.hl7.davinci.cql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.fhir.cql.EvaluationSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import kotlinx.io.Buffer;
import kotlinx.io.Source;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ElmCompilerTest {

  private ElmCompiler elmCompiler;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    EvaluationSettings settings = EvaluationSettings.getDefault();
    elmCompiler = new ElmCompiler(settings);
  }

  @Nested
  @DisplayName("Valid CQL compilation")
  class ValidCqlTests {

    @Test
    @DisplayName("Produces valid ELM JSON from simple CQL")
    void validCql_producesElmJson() throws Exception {
      String cql = """
          library TestLibrary version '1.0.0'
          using FHIR version '4.0.1'
          define TestExpression: true
          """;

      String elmJson = elmCompiler.compile(cql, null);

      assertNotNull(elmJson);
      JsonNode elm = objectMapper.readTree(elmJson);
      assertTrue(elm.has("library"), "ELM JSON should have a 'library' root element");
      assertEquals("TestLibrary", elm.get("library").get("identifier").get("id").asText());
      assertEquals("1.0.0", elm.get("library").get("identifier").get("version").asText());
    }
  }

  @Nested
  @DisplayName("Invalid CQL compilation")
  class InvalidCqlTests {

    @Test
    @DisplayName("Throws ElmCompilationException for invalid CQL")
    void invalidCql_throwsException() {
      String invalidCql = """
          library TestLibrary version '1.0.0'
          using FHIR version '4.0.1'
          define BadExpression: this is not valid CQL syntax at all
          """;

      ElmCompilationException ex = assertThrows(ElmCompilationException.class,
          () -> elmCompiler.compile(invalidCql, null));

      assertNotNull(ex.getMessage());
      assertFalse(ex.getErrors().isEmpty(), "Should contain compiler errors");
    }
  }

  @Nested
  @DisplayName("CQL with include dependencies")
  class IncludeDependencyTests {

    @Test
    @DisplayName("Compiles CQL with include when provider resolves dependency")
    void includeWithProvider_succeeds() throws Exception {
      String helperCql = """
          library HelperLibrary version '1.0.0'
          define HelperExpression: 42
          """;

      String mainCql = """
          library MainLibrary version '1.0.0'
          include HelperLibrary version '1.0.0'
          define MainExpression: HelperLibrary.HelperExpression
          """;

      LibrarySourceProvider provider = new LibrarySourceProvider() {
        @Override
        public Source getLibrarySource(VersionedIdentifier identifier) {
          if ("HelperLibrary".equals(identifier.getId())) {
            Buffer buffer = new Buffer();
            buffer.write(helperCql.getBytes(StandardCharsets.UTF_8), 0, helperCql.getBytes(StandardCharsets.UTF_8).length);
            return buffer;
          }
          return null;
        }
      };

      String elmJson = elmCompiler.compile(mainCql, provider);

      assertNotNull(elmJson);
      JsonNode elm = objectMapper.readTree(elmJson);
      assertEquals("MainLibrary", elm.get("library").get("identifier").get("id").asText());
    }

    @Test
    @DisplayName("Throws when include dependency cannot be resolved")
    void includeWithoutProvider_throws() {
      String mainCql = """
          library MainLibrary version '1.0.0'
          include MissingLibrary version '1.0.0'
          define MainExpression: MissingLibrary.SomeExpression
          """;

      assertThrows(ElmCompilationException.class,
          () -> elmCompiler.compile(mainCql, null));
    }
  }
}
