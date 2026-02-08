package org.hl7.davinci.cdshooks.shared;

import org.hl7.davinci.common.FhirUtil;

/**
 * Shared constants for CRD CDS Hooks implementation.
 */
public final class CrdConstants {

  /** @see FhirUtil#COVERAGE_INFO_EXT_URL */
  public static final String COVERAGE_INFO_EXT_URL = FhirUtil.COVERAGE_INFO_EXT_URL;
  public static final String CARD_TYPE_SYSTEM = "http://terminology.hl7.org/CodeSystem/cdshooks-card-type";

  private CrdConstants() {
  }
}
