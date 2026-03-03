package org.hl7.davinci.dtr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.davinci.common.FhirUtil;
import org.hl7.davinci.cql.DaoLibrarySourceProvider;
import org.hl7.davinci.cql.ElmCompiler;
import org.hl7.davinci.cql.ElmCompilationException;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.RelatedArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static org.hl7.davinci.common.FhirConstants.*;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.util.UrlUtil;

/**
 * Resolves Library resources referenced by Questionnaire cqf-library extensions.
 * Recursively follows relatedArtifact depends-on chains, validates ELM content,
 * and rewrites references to version-specific canonicals.
 */
@Component
public class DtrLibraryResolver {

  private static final Logger logger = LoggerFactory.getLogger(DtrLibraryResolver.class);

  private static final String CQL_CONTENT_TYPE = "text/cql";
  private static final String ELM_CONTENT_TYPE = "application/elm+json";

  private final DaoRegistry daoRegistry;
  private final ElmCompiler elmCompiler;
  private final DaoLibrarySourceProvider librarySourceProvider;

  public DtrLibraryResolver(DaoRegistry daoRegistry, ElmCompiler elmCompiler,
      DaoLibrarySourceProvider librarySourceProvider) {
    this.daoRegistry = daoRegistry;
    this.elmCompiler = elmCompiler;
    this.librarySourceProvider = librarySourceProvider;
  }

  public record LibraryResolution(List<Library> libraries, List<String> warnings) {}

  /**
   * Resolve all Libraries referenced by the Questionnaire's cqf-library extensions,
   * recursively following relatedArtifact depends-on chains.
   */
  public LibraryResolution resolveLibraries(Questionnaire questionnaire) {
    List<String> warnings = new ArrayList<>();
    Map<String, Library> resolved = new LinkedHashMap<>();
    Set<String> visited = new HashSet<>();
    Set<String> seenNames = new HashSet<>();

    // Extract cqf-library extension canonicals from the Questionnaire
    for (Extension ext : questionnaire.getExtension()) {
      if (CQF_LIBRARY_EXT.equals(ext.getUrl()) && ext.hasValue()) {
        String canonical = ext.getValue().primitiveValue();
        resolveLibraryRecursive(canonical, resolved, visited, seenNames, warnings);
      }
    }

    // Keep Library.url canonical in canonical form (without |version). Expression
    // references in Questionnaires commonly use base canonical URLs.
    for (Library library : resolved.values()) {
      // Ensure CQL/ELM content uses inline data, not URL references (spec-99)
      ensureInlineContent(library, warnings);
    }

    return new LibraryResolution(new ArrayList<>(resolved.values()), warnings);
  }

  private void resolveLibraryRecursive(String canonical, Map<String, Library> resolved,
      Set<String> visited, Set<String> seenNames, List<String> warnings) {

    if (canonical == null || visited.contains(canonical)) {
      return;
    }
    visited.add(canonical);

    // Skip classpath-provided libraries (e.g. FHIRHelpers) - not stored in the repository
    String[] parts = FhirUtil.parseCanonical(canonical);
    if (parts.length > 0) {
      String baseUrl = parts[0];
      int lastSlash = baseUrl.lastIndexOf('/');
      if (lastSlash >= 0) {
        String name = baseUrl.substring(lastSlash + 1);
        if (DaoLibrarySourceProvider.isClasspathLibrary(name)) {
          logger.debug("Skipping classpath library: {}", canonical);
          return;
        }
      }
    }

    Library library = FhirUtil.resolveByCanonical(daoRegistry, Library.class, canonical);
    if (library == null) {
      String warning = "Library not found: " + canonical;
      logger.warn(warning);
      warnings.add(warning);
      return;
    }

    // Use version-specific canonical as the dedup key
    String key = FhirUtil.toVersionSpecific(library.getUrl(), library.getVersion());
    if (resolved.containsKey(key)) {
      return;
    }

    // Check name uniqueness (spec-98)
    if (library.hasName()) {
      if (!seenNames.add(library.getName())) {
        String warning = "Duplicate library name in package: " + library.getName();
        logger.warn(warning);
        warnings.add(warning);
      }
    }

    ensureElm(library, warnings);

    Library normalized = library.copy();
    normalizeCanonicalUrl(normalized);
    resolved.put(key, normalized);

    // Recursively follow relatedArtifact depends-on
    if (library.hasRelatedArtifact()) {
      for (RelatedArtifact artifact : library.getRelatedArtifact()) {
        if (artifact.getType() == RelatedArtifact.RelatedArtifactType.DEPENDSON
            && artifact.hasResource()) {
          String depCanonical = artifact.getResource();
          resolveLibraryRecursive(depCanonical, resolved, visited, seenNames, warnings);
        }
      }
    }
  }

  private void ensureElm(Library library, List<String> warnings) {
    boolean hasElm = library.getContent().stream()
        .anyMatch(c -> ELM_CONTENT_TYPE.equals(c.getContentType()) && c.hasData());
    if (hasElm) {
      return;
    }

    boolean hasInlineCql = library.getContent().stream()
        .anyMatch(c -> CQL_CONTENT_TYPE.equals(c.getContentType()) && c.hasData());
    if (!hasInlineCql) {
      return;
    }

    try {
      elmCompiler.compileAndAttachElm(library, librarySourceProvider);
    } catch (ElmCompilationException e) {
      String warning = "ELM compilation failed for Library " + library.getId() + ": " + e.getMessage();
      logger.warn(warning);
      warnings.add(warning);
    }
  }

  private void ensureInlineContent(Library library, List<String> warnings) {
    for (Attachment content : library.getContent()) {
      if (!isCqlOrElmContent(content)) {
        continue;
      }
      if (content.hasUrl() && !content.hasData()) {
        String warning = "Library " + library.getId()
            + " has content with URL reference instead of inline data (spec-99)";
        logger.warn(warning);
        warnings.add(warning);
      }
    }
  }

  private boolean isCqlOrElmContent(Attachment content) {
    String type = content.getContentType();
    return CQL_CONTENT_TYPE.equals(type) || ELM_CONTENT_TYPE.equals(type);
  }

  private void normalizeCanonicalUrl(Library library) {
    if (!library.hasUrl()) {
      return;
    }
    String normalized = UrlUtil.normalizeCanonicalUrlForComparison(library.getUrl());
    if (normalized != null && !normalized.isBlank()) {
      library.setUrl(normalized);
    }
  }
}
