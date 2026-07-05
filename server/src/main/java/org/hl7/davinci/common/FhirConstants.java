package org.hl7.davinci.common;

import java.util.Set;

/**
 * Constants for standard code systems, extensions, and identifier systems
 * not defined by any specific Da Vinci IG (CRD, DTR, or PAS).
 */
public final class FhirConstants {

  private FhirConstants() {}

  // ===== FHIR Core Extensions =====

  public static final String CQF_LIBRARY_EXT =
      "http://hl7.org/fhir/StructureDefinition/cqf-library";

  // ===== SDC (Structured Data Capture) Extensions =====

  public static final String QUESTIONNAIRE_ADAPTIVE_EXT =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-questionnaireAdaptive";
  public static final String SUB_QUESTIONNAIRE_EXT =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-subQuestionnaire";
  public static final String LAUNCH_CONTEXT_EXT =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-launchContext";

  public static final Set<String> CQL_EXPRESSION_EXT_URLS = Set.of(
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression",
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-calculatedExpression",
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-candidateExpression",
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-contextExpression",
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-enableWhenExpression");

  // ===== Clinical Terminology Systems =====

  public static final String CPT_SYSTEM = "http://www.ama-assn.org/go/cpt";
  public static final String HCPCS_SYSTEM = "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";
  public static final String ICD9CM_SYSTEM = "http://terminology.hl7.org/CodeSystem/icd9cm";
  public static final String ICD10_SYSTEM = "http://www.cms.gov/Medicare/Coding/ICD10";
  public static final String NDC_SYSTEM = "http://hl7.org/fhir/sid/ndc";
  public static final String RXNORM_SYSTEM = "http://www.nlm.nih.gov/research/umls/rxnorm";
  public static final String SNOMED_SYSTEM = "http://snomed.info/sct";

  // ===== HL7 Terminology Code Systems =====

  public static final String ADJUDICATION_SYSTEM = "http://terminology.hl7.org/CodeSystem/adjudication";
  public static final String CARD_TYPE_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/cdshooks-card-type";
  public static final String CLAIM_TYPE_SYSTEM = "http://terminology.hl7.org/CodeSystem/claim-type";
  public static final String COVERAGE_CLASS_SYSTEM = "http://terminology.hl7.org/CodeSystem/coverage-class";
  public static final String DATA_ABSENT_REASON_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/data-absent-reason";
  public static final String ORGANIZATION_TYPE_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/organization-type";
  public static final String PROCESS_PRIORITY_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/processpriority";
  public static final String RELATED_CLAIM_RELATIONSHIP_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/ex-relatedclaimrelationship";
  public static final String SUBSCRIBER_RELATIONSHIP_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/subscriber-relationship";
  public static final String USAGE_CONTEXT_TYPE_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/usage-context-type";

  // ===== HL7 v2/v3 Code Systems =====

  public static final String V2_DEGREE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v2-0360";
  public static final String V3_PARTICIPATION_TYPE_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/v3-ParticipationType";

  // ===== X12 Code Systems =====

  // X12 306 Review Action Codes (https://codesystem.x12.org/005010/306)
  public static final String X12_REVIEW_CODE_SYSTEM = "https://codesystem.x12.org/005010/306";
  public static final String REVIEW_CODE_A1 = "A1"; // Certified in Total
  public static final String REVIEW_CODE_A2 = "A2"; // Not Certified
  public static final String REVIEW_CODE_A3 = "A3"; // Not Required
  public static final String REVIEW_CODE_A4 = "A4"; // Pended
  public static final String REVIEW_CODE_A6 = "A6"; // Modified

  // X12 1322 Certification Type codes (https://codesystem.x12.org/005010/1322)
  public static final String X12_CERT_TYPE_SYSTEM = "https://codesystem.x12.org/005010/1322";
  public static final String CERT_TYPE_INITIAL = "I";
  public static final String CERT_TYPE_RENEWAL = "R";
  public static final String CERT_TYPE_CANCEL = "3";

  // X12 1525 Service Type codes (https://codesystem.x12.org/005010/1525)
  public static final String X12_SERVICE_TYPE_SYSTEM = "https://codesystem.x12.org/005010/1525";

  // X12 1365 Requested Service Type codes (https://codesystem.x12.org/005010/1365)
  public static final String X12_REQUESTED_SERVICE_SYSTEM = "https://codesystem.x12.org/005010/1365";

  // ===== CMS Place of Service =====

  public static final String CMS_PLACE_OF_SERVICE_SYSTEM =
      "https://www.cms.gov/Medicare/Coding/place-of-service-codes/Place_of_Service_Code_Set";

  // ===== Identifier Systems =====

  public static final String NPI_SYSTEM = "http://hl7.org/fhir/sid/us-npi";
  public static final String IDENTIFIER_TYPE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v2-0203";
  public static final String MB_TYPE_CODE = "MB";

  // ===== VSAC =====

  public static final String VSAC_VALUESET_PREFIX = "http://cts.nlm.nih.gov/fhir/ValueSet/";
}
