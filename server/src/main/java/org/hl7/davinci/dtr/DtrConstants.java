package org.hl7.davinci.dtr;

import java.util.Set;

/**
 * Shared FHIR URL constants for the DTR implementation.
 * Profiles, extensions, and canonical prefixes used across multiple DTR classes.
 */
public final class DtrConstants {

  private DtrConstants() {}

  // ===== DTR IG Profiles =====

  public static final String Q_ADAPT_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt";
  public static final String Q_ADAPT_SEARCH_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt-search";
  public static final String QR_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaireresponse";
  public static final String QR_ADAPT_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaireresponse-adapt";
  public static final String QPACKAGE_BUNDLE_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/DTR-QPackageBundle";
  public static final String QPACKAGE_OUTPUT_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-qpackage-output-parameters";
  public static final String QPACKAGE_INPUT_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-qpackage-input-parameters";
  public static final String NEXT_QUESTION_OUTPUT_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-next-question-output-parameters";

  // ===== DTR IG Extensions =====

  public static final String QR_COVERAGE_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-coverage";
  public static final String QR_CONTEXT_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-context";
  public static final String INFO_ORIGIN_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/information-origin";
  public static final String INTENDED_USE_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/intendedUse";
  public static final String ALT_EXPRESSION_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/alternativeExpression";

  // ===== SDC Extensions =====

  public static final String QUESTIONNAIRE_ADAPTIVE_EXT =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-questionnaireAdaptive";
  public static final String SUB_QUESTIONNAIRE_EXT =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-subQuestionnaire";

  public static final Set<String> CQL_EXPRESSION_EXT_URLS = Set.of(
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression",
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-calculatedExpression",
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-candidateExpression",
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-contextExpression",
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-enableWhenExpression");

  // ===== FHIR Core Extensions =====

  public static final String CQF_LIBRARY_EXT =
      "http://hl7.org/fhir/StructureDefinition/cqf-library";

  // ===== Custom Headers =====

  public static final String ADAPTIVE_MODE_HEADER = "X-DTR-Adaptive-Mode";

  // ===== Canonical Prefixes =====

  public static final String DTR_QUESTIONNAIRE_PREFIX =
      "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/";
}
