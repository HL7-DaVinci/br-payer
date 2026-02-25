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

  public static final String DOC_REASON_SYSTEM =
      "http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp";
}
