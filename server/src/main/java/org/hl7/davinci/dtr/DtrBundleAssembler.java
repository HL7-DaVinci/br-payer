package org.hl7.davinci.dtr;

import java.util.List;
import java.util.Set;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ValueSet;
import org.springframework.stereotype.Component;

import static org.hl7.davinci.dtr.DtrConstants.*;

import ca.uhn.fhir.jpa.starter.AppProperties;

/**
 * Assembles collection Bundles for DTR $questionnaire-package responses.
 * Enforces Questionnaire-first ordering (dtrb-1), allowed resource types,
 * and validates the presence of alternativeExpression extensions on CQL expressions.
 */
@Component
public class DtrBundleAssembler {

  private static final Set<String> ALLOWED_TYPES = Set.of(
      "Questionnaire", "Library", "ValueSet", "QuestionnaireResponse");

  private final AppProperties appProperties;

  public DtrBundleAssembler(AppProperties appProperties) {
    this.appProperties = appProperties;
  }

  public record BundleResult(Bundle bundle, String error) {}

  /**
   * Assemble a collection Bundle with Questionnaire first (dtrb-1),
   * followed by Libraries, ValueSets, and QuestionnaireResponse.
   * Validates alternativeExpression presence before assembly.
   *
   * @return BundleResult with either a valid Bundle or an error message
   */
  public BundleResult assembleBundle(
      Questionnaire questionnaire,
      List<Library> libraries,
      List<ValueSet> valueSets,
      QuestionnaireResponse qr) {

    // Validate alternativeExpression presence on CQL expressions
    String validationError = validateAlternativeExpressions(questionnaire);
    if (validationError != null) {
      return new BundleResult(null, validationError);
    }

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.getMeta().addProfile(QPACKAGE_BUNDLE_PROFILE);

    String serverBase = getServerBase();

    // Questionnaire first (dtrb-1 constraint)
    addEntry(bundle, questionnaire, serverBase);

    // Libraries
    if (libraries != null) {
      for (Library library : libraries) {
        addEntry(bundle, library, serverBase);
      }
    }

    // ValueSets
    if (valueSets != null) {
      for (ValueSet vs : valueSets) {
        addEntry(bundle, vs, serverBase);
      }
    }

    // QuestionnaireResponse
    if (qr != null) {
      addEntry(bundle, qr, serverBase);
    }

    return new BundleResult(bundle, null);
  }

  private void addEntry(Bundle bundle, Resource resource, String serverBase) {
    String resourceType = resource.fhirType();
    if (!ALLOWED_TYPES.contains(resourceType)) {
      throw new IllegalArgumentException("Resource type not allowed in DTR package bundle: " + resourceType);
    }

    Bundle.BundleEntryComponent entry = bundle.addEntry();
    entry.setResource(resource);

    // Stable fullUrl using server base
    String idPart = resource.getIdElement().getIdPart();
    if (idPart != null && serverBase != null) {
      entry.setFullUrl(serverBase + "/" + resourceType + "/" + idPart);
    }
  }

  /**
   * Walk all Questionnaire items and check that every CQL expression extension
   * has an alternativeExpression sub-extension with language application/elm+json.
   * Returns an error message if validation fails, null if valid.
   */
  private String validateAlternativeExpressions(Questionnaire questionnaire) {
    // Check if questionnaire has any CQL expressions at all
    boolean hasCqlExpressions = hasCqlExpressions(questionnaire.getItem());
    if (!hasCqlExpressions) {
      return null; // No CQL expressions to validate
    }

    String missing = findMissingAlternativeExpression(questionnaire.getItem());
    if (missing != null) {
      return "Questionnaire " + questionnaire.getUrl()
          + " excluded from package: CQL expression missing alternativeExpression (ELM) at " + missing;
    }
    return null;
  }

  private boolean hasCqlExpressions(List<QuestionnaireItemComponent> items) {
    for (QuestionnaireItemComponent item : items) {
      for (Extension ext : item.getExtension()) {
        if (CQL_EXPRESSION_EXT_URLS.contains(ext.getUrl())) {
          return true;
        }
      }
      if (item.hasItem() && hasCqlExpressions(item.getItem())) {
        return true;
      }
    }
    return false;
  }

  private String findMissingAlternativeExpression(List<QuestionnaireItemComponent> items) {
    for (QuestionnaireItemComponent item : items) {
      for (Extension ext : item.getExtension()) {
        if (CQL_EXPRESSION_EXT_URLS.contains(ext.getUrl())) {
          // Check as sibling sub-extension (legacy location)
          Extension altExt = ext.getExtensionByUrl(ALT_EXPRESSION_EXT);
          // Check on the Expression datatype's own extensions (correct FHIR structure)
          if (altExt == null && ext.hasValue()) {
            altExt = ext.getValue().getExtensionByUrl(ALT_EXPRESSION_EXT);
          }
          if (altExt == null) {
            return "item '" + item.getLinkId() + "' extension '" + ext.getUrl() + "'";
          }
        }
      }
      if (item.hasItem()) {
        String missing = findMissingAlternativeExpression(item.getItem());
        if (missing != null) {
          return missing;
        }
      }
    }
    return null;
  }

  private String getServerBase() {
    String base = appProperties.getServer_address();
    if (base != null && base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base;
  }
}
