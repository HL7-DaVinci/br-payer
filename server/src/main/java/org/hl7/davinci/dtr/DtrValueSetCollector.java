package org.hl7.davinci.dtr;

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
      ValueSet vs = DtrFhirUtil.resolveByCanonical(daoRegistry, ValueSet.class, url);

      // Fall back to validation support chain (DefaultProfileValidationSupport, VSAC, etc.)
      if (vs == null && validationSupport != null) {
        String baseUrl = DtrFhirUtil.parseCanonical(url)[0];
        IBaseResource fetched = validationSupport.fetchValueSet(baseUrl);
        if (fetched instanceof ValueSet) {
          vs = (ValueSet) fetched;
          logger.debug("ValueSet resolved via validation support: {}", url);
        }
      }

      if (vs == null) {
        String warning = "ValueSet not found: " + url;
        logger.warn(warning);
        warnings.add(warning);
        continue;
      }

      // Pre-expand small ValueSets
      tryExpand(vs, warnings);

      // Rewrite URL to version-specific
      String versionSpecific = DtrFhirUtil.toVersionSpecific(vs.getUrl(), vs.getVersion());
      if (versionSpecific != null && !versionSpecific.equals(vs.getUrl())) {
        vs.setUrl(versionSpecific);
      }

      valueSets.add(vs);
    }

    return new ValueSetCollection(valueSets, warnings);
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
}
