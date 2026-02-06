package org.hl7.davinci.cql;

import java.nio.charset.StandardCharsets;

import org.cqframework.cql.cql2elm.CqlCompilerOptions;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.LibraryManager;
import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.cqframework.cql.cql2elm.ModelManager;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Library;
import org.opencds.cqf.fhir.cql.EvaluationSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Compiles CQL text to ELM JSON using the CQL-to-ELM translator.
 * Reuses CqlCompilerOptions from the configured EvaluationSettings bean
 * for consistency with CQL engine runtime compilation.
 */
@Component
public class ElmCompiler {

  private static final Logger logger = LoggerFactory.getLogger(ElmCompiler.class);

  private static final String CQL_CONTENT_TYPE = "text/cql";
  private static final String ELM_CONTENT_TYPE = "application/elm+json";

  private final CqlCompilerOptions compilerOptions;

  public ElmCompiler(EvaluationSettings evaluationSettings) {
    this.compilerOptions = evaluationSettings.getCqlOptions().getCqlCompilerOptions();
  }

  /**
   * Compile CQL text to ELM JSON.
   *
   * @param cqlText               raw CQL source
   * @param librarySourceProvider  resolves CQL include dependencies (nullable for standalone CQL)
   * @return ELM JSON string
   * @throws ElmCompilationException if compilation fails
   */
  public String compile(String cqlText, LibrarySourceProvider librarySourceProvider) {
    boolean disableDefaultModelInfo = compilerOptions.getOptions()
        .contains(CqlCompilerOptions.Options.DisableDefaultModelInfoLoad);
    ModelManager modelManager = new ModelManager(disableDefaultModelInfo);
    LibraryManager libraryManager = new LibraryManager(modelManager, compilerOptions);

    if (librarySourceProvider != null) {
      libraryManager.getLibrarySourceLoader().registerProvider(librarySourceProvider);
    }

    CqlTranslator translator = CqlTranslator.fromText(cqlText, libraryManager);

    if (!translator.getErrors().isEmpty()) {
      String errorSummary = translator.getErrors().stream()
          .map(e -> e.getMessage())
          .reduce((a, b) -> a + "; " + b)
          .orElse("Unknown compilation error");
      throw new ElmCompilationException(
          "CQL compilation failed: " + errorSummary,
          translator.getErrors());
    }

    return translator.toJson();
  }

  /**
   * Compile and attach ELM to a Library resource.
   * Finds the text/cql content attachment, compiles it, and adds an
   * application/elm+json content attachment. Skips if ELM is already present.
   *
   * @param library               the Library resource to enhance
   * @param librarySourceProvider  resolves CQL include dependencies
   * @return true if ELM was compiled and attached
   */
  public boolean compileAndAttachElm(Library library, LibrarySourceProvider librarySourceProvider) {
    // Skip if ELM already present
    boolean hasElm = library.getContent().stream()
        .anyMatch(c -> ELM_CONTENT_TYPE.equals(c.getContentType()) && c.hasData());
    if (hasElm) {
      logger.debug("Library/{} already has ELM content, skipping compilation", library.getId());
      return false;
    }

    // Find the CQL content attachment
    Attachment cqlAttachment = library.getContent().stream()
        .filter(c -> CQL_CONTENT_TYPE.equals(c.getContentType()) && c.hasData())
        .findFirst()
        .orElse(null);

    if (cqlAttachment == null) {
      logger.debug("Library/{} has no CQL content to compile", library.getId());
      return false;
    }

    String cqlText = new String(cqlAttachment.getData(), StandardCharsets.UTF_8);
    String elmJson = compile(cqlText, librarySourceProvider);

    Attachment elmAttachment = new Attachment();
    elmAttachment.setContentType(ELM_CONTENT_TYPE);
    elmAttachment.setData(elmJson.getBytes(StandardCharsets.UTF_8));
    library.addContent(elmAttachment);

    logger.info("Compiled and attached ELM for Library/{}", library.getId());
    return true;
  }
}
