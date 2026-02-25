package org.hl7.davinci.dtr;

import static org.hl7.davinci.common.FhirConstants.SNOMED_SYSTEM;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.DataRequirement;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.hl7.fhir.r4.model.ValueSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDaoValueSet;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.util.UrlUtil;

/**
 * Collects ValueSet resources referenced by Questionnaire items and Library data requirements.
 * Pre-expands small ValueSets (fewer than a configurable threshold of codes)
 * to optimize DTR client-side rendering.
 */
@Component
public class DtrValueSetCollector {

  private static final Logger logger = LoggerFactory.getLogger(DtrValueSetCollector.class);

  private static final int EXPANSION_THRESHOLD = 40;

  private final DaoRegistry daoRegistry;
  private final IValidationSupport validationSupport;

  public DtrValueSetCollector(DaoRegistry daoRegistry, IValidationSupport validationSupport) {
    this.daoRegistry = daoRegistry;
    this.validationSupport = validationSupport;
  }

  public record ValueSetCollection(List<ValueSet> valueSets, List<String> warnings) {}

  /**
   * Collect ValueSets from Questionnaire answerValueSet references and Library dataRequirement
   * code filters. Deduplicates by URL and pre-expands small ValueSets.
   */
  public ValueSetCollection collectValueSets(Questionnaire questionnaire, List<Library> libraries) {
    List<String> warnings = new ArrayList<>();
    Map<String, String> valueSetUrls = new LinkedHashMap<>(); // url -> source description

    // Collect from Questionnaire items
    collectFromItems(questionnaire.getItem(), valueSetUrls);

    // Collect from Library dataRequirements
    if (libraries != null) {
      for (Library library : libraries) {
        collectFromLibrary(library, valueSetUrls);
      }
    }

    // Resolve and optionally expand each ValueSet
    List<ValueSet> valueSets = new ArrayList<>();
    for (String url : valueSetUrls.keySet()) {
      ValueSet vs = resolveAndPersist(url, warnings);

      if (vs == null) {
        continue;
      }

      // ValueSet.description is required. VSAC-sourced ValueSets often omit it
      // https://hl7.org/fhir/R4/shareablevalueset.html
      if (!vs.hasDescription()) {
        vs.setDescription(vs.hasTitle() ? vs.getTitle() : vs.hasName() ? vs.getName() : "ValueSet " + url);
      }

      normalizeCanonicalUrl(vs);
      sanitizeInvalidSnomedConceptFilters(vs);

      // Pre-expand small ValueSets
      tryExpand(vs, warnings);

      valueSets.add(vs);
    }

    return new ValueSetCollection(valueSets, warnings);
  }

  /**
   * Resolve a ValueSet by canonical URL: first from JPA, then via the validation support chain
   * (which includes VSAC). If fetched externally, expands and persists to JPA so subsequent
   * lookups are local. Returns null if the ValueSet cannot be resolved.
   */
  public ValueSet resolveAndPersist(String url, List<String> warnings) {
    ValueSet vs = DtrFhirUtil.resolveByCanonical(daoRegistry, ValueSet.class, url);

    if (vs == null && validationSupport != null) {
      String baseUrl = DtrFhirUtil.parseCanonical(url)[0];
      IBaseResource fetched = validationSupport.fetchValueSet(baseUrl);
      if (fetched instanceof ValueSet) {
        vs = (ValueSet) fetched;
        logger.debug("ValueSet resolved via validation support: {}", url);
        persistExternalValueSet(vs, warnings);
      }
    }

    if (vs == null) {
      String warning = "ValueSet not found: " + url;
      logger.warn(warning);
      warnings.add(warning);
    }

    return vs;
  }

  /**
   * Persist an externally-fetched ValueSet (e.g. from VSAC) to the JPA store so downstream
   * consumers like the CQL engine's RepositoryTerminologyProvider can find it via repository search.
   * Expands the ValueSet first so the CQL engine can use pre-existing expansion elements.
   */
  private void persistExternalValueSet(ValueSet vs, List<String> warnings) {
    IFhirResourceDaoValueSet<ValueSet> vsDao =
        (IFhirResourceDaoValueSet<ValueSet>) daoRegistry.getResourceDao(ValueSet.class);

    // Expand before persisting so CQL naive expansion has something to work with
    if (!vs.hasExpansion()) {
      try {
        ValueSet expanded = vsDao.expand(vs, null);
        if (expanded != null && expanded.hasExpansion()) {
          vs.setExpansion(expanded.getExpansion());
        }
      } catch (Exception e) {
        String warning = "ValueSet expansion failed during persist for " + vs.getUrl() + ": " + e.getMessage();
        logger.warn(warning);
        warnings.add(warning);
      }
    }

    try {
      vsDao.update(vs, new SystemRequestDetails());
      logger.debug("Persisted external ValueSet to JPA store: {}", vs.getUrl());
    } catch (Exception e) {
      String warning = "Failed to persist external ValueSet " + vs.getUrl() + ": " + e.getMessage();
      logger.warn(warning);
      warnings.add(warning);
    }
  }

  private void collectFromItems(List<QuestionnaireItemComponent> items, Map<String, String> urls) {
    if (items == null) {
      return;
    }
    for (QuestionnaireItemComponent item : items) {
      if (item.hasAnswerValueSet()) {
        urls.putIfAbsent(item.getAnswerValueSet(), "Questionnaire item " + item.getLinkId());
      }
      if (item.hasItem()) {
        collectFromItems(item.getItem(), urls);
      }
    }
  }

  private void collectFromLibrary(Library library, Map<String, String> urls) {
    if (!library.hasDataRequirement()) {
      return;
    }
    for (DataRequirement dr : library.getDataRequirement()) {
      if (dr.hasCodeFilter()) {
        for (DataRequirement.DataRequirementCodeFilterComponent cf : dr.getCodeFilter()) {
          if (cf.hasValueSet()) {
            urls.putIfAbsent(cf.getValueSet(), "Library " + library.getId());
          }
        }
      }
    }
  }

  private void tryExpand(ValueSet vs, List<String> warnings) {
    if (vs.hasExpansion()) {
      return; // Already expanded
    }

    int conceptCount = countComposeConcepts(vs);
    if (conceptCount < 0 || conceptCount >= EXPANSION_THRESHOLD) {
      return; // Too large or unknown size
    }

    try {
      IFhirResourceDaoValueSet<ValueSet> vsDao =
          (IFhirResourceDaoValueSet<ValueSet>) daoRegistry.getResourceDao(ValueSet.class);

      ValueSet expanded = vsDao.expand(vs, null);
      if (expanded != null && expanded.hasExpansion()) {
        vs.setExpansion(expanded.getExpansion());
        logger.debug("Pre-expanded ValueSet {} ({} concepts)", vs.getUrl(), conceptCount);
      }
    } catch (Exception e) {
      String warning = "ValueSet expansion failed for " + vs.getUrl() + ": " + e.getMessage();
      logger.warn(warning);
      warnings.add(warning);
    }
  }

  /**
   * Some externally sourced ValueSets encode exact SNOMED concept matches with
   * filter op "=" on property "concept", which is not a permitted SNOMED filter
   * operation in R4 validation. Convert those filters to explicit concepts.
   */
  private void sanitizeInvalidSnomedConceptFilters(ValueSet vs) {
    if (!vs.hasCompose()) {
      return;
    }

    for (ValueSet.ConceptSetComponent include : vs.getCompose().getInclude()) {
      if (!SNOMED_SYSTEM.equals(include.getSystem()) || !include.hasFilter()) {
        continue;
      }

      List<ValueSet.ConceptSetFilterComponent> filters = new ArrayList<>(include.getFilter());
      for (ValueSet.ConceptSetFilterComponent filter : filters) {
        String opCode = filter.hasOp() ? filter.getOp().toCode() : null;
        boolean equalOp = "=".equals(opCode) || filter.getOp() == ValueSet.FilterOperator.EQUAL;
        if ("concept".equals(filter.getProperty())
            && equalOp
            && filter.hasValue()) {
          include.getFilter().remove(filter);
          if (!hasConceptCode(include, filter.getValue())) {
            include.addConcept().setCode(filter.getValue());
          }
        }
      }
    }
  }

  /**
   * Count explicit concepts in compose.include[].concept[].
   * Returns -1 if size is unknown (no explicit concepts, only filters/imports).
   */
  private int countComposeConcepts(ValueSet vs) {
    if (!vs.hasCompose() || !vs.getCompose().hasInclude()) {
      return -1;
    }
    int total = 0;
    boolean hasExplicitConcepts = false;
    for (ValueSet.ConceptSetComponent include : vs.getCompose().getInclude()) {
      if (include.hasConcept()) {
        total += include.getConcept().size();
        hasExplicitConcepts = true;
      }
    }
    return hasExplicitConcepts ? total : -1;
  }

  private void normalizeCanonicalUrl(ValueSet vs) {
    if (!vs.hasUrl()) {
      return;
    }
    String normalized = UrlUtil.normalizeCanonicalUrlForComparison(vs.getUrl());
    if (normalized != null && !normalized.isBlank()) {
      vs.setUrl(normalized);
    }
  }

  private boolean hasConceptCode(ValueSet.ConceptSetComponent include, String code) {
    if (!include.hasConcept() || code == null) {
      return false;
    }
    return include.getConcept().stream().anyMatch(c -> code.equals(c.getCode()));
  }
}
