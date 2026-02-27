package org.hl7.davinci.common;

/**
 * Shared constants for the CRD (Coverage Requirements Discovery) IG.
 * Profiles, extensions, and code systems used across CRD-related classes.
 */
public final class CrdConstants {

  private CrdConstants() {}

  // ===== CRD IG Extensions =====

  public static final String COVERAGE_INFO_EXT =
      "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";

  // ===== CRD IG Code Systems =====

  // In CRD 2.2.0 this becomes:
  // http://hl7.org/fhir/us/davinci-crd/CodeSystem/coverage-information-codes
  // However DTR 2.2.0 is currently bound to CRD 2.1.0 which still uses "temp"
  public static final String DOC_REASON_SYSTEM =
      "http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp";
}
