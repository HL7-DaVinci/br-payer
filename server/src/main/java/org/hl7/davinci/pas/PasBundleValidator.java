package org.hl7.davinci.pas;

import org.hl7.davinci.common.FhirConstants;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;

/**
 * Validates PAS request bundles per PAS IG profile constraints.
 * Checks bundle type, first entry type, Claim.use, and required references.
 */
@Component
public class PasBundleValidator {

  /**
   * Validates a $submit request bundle and extracts the Claim.
   *
   * @throws IllegalArgumentException if the bundle is invalid
   */
  public Claim validateSubmitBundle(Bundle bundle) {
    Claim claim = validateCommon(bundle);
    if (!claim.hasItem() || claim.getItem().isEmpty()) {
      throw new IllegalArgumentException("Claim must have at least one item");
    }
    if (!bundle.hasIdentifier()) {
      throw new IllegalArgumentException("Bundle.identifier is required");
    }
    if (!bundle.hasTimestamp()) {
      throw new IllegalArgumentException("Bundle.timestamp is required");
    }
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      if (!entry.hasFullUrl() || entry.getFullUrl().isBlank()) {
        throw new IllegalArgumentException("All Bundle.entry elements must have a fullUrl");
      }
    }
    return claim;
  }

  /**
   * Validates a $inquire request bundle and extracts the Claim (inquiry).
   * Enforces PAS inquiry request bundle profile.
   *
   * @throws IllegalArgumentException if the bundle is invalid
   */
  public Claim validateInquiryBundle(Bundle bundle) {
    Claim claim = validateCommon(bundle);
    if (!bundle.hasIdentifier()) {
      throw new IllegalArgumentException("Bundle.identifier is required");
    }
    if (!bundle.hasTimestamp()) {
      throw new IllegalArgumentException("Bundle.timestamp is required");
    }
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      if (!entry.hasFullUrl() || entry.getFullUrl().isBlank()) {
        throw new IllegalArgumentException("All Bundle.entry elements must have a fullUrl");
      }
    }
    if (!claim.hasIdentifier()) {
      throw new IllegalArgumentException("Claim.identifier is required for inquiry");
    }
    if (claim.getStatus() != Claim.ClaimStatus.ACTIVE) {
      throw new IllegalArgumentException(
          "Claim.status must be 'active', got: " + claim.getStatus());
    }
    if (!claim.hasCreated()) {
      throw new IllegalArgumentException("Claim.created is required");
    }
    validateMemberIdentifier(bundle);
    return claim;
  }

  private Claim validateCommon(Bundle bundle) {
    if (bundle == null) {
      throw new IllegalArgumentException("Request bundle is required");
    }
    if (bundle.getType() != Bundle.BundleType.COLLECTION) {
      throw new IllegalArgumentException(
          "Bundle type must be 'collection', got: " + bundle.getType());
    }
    if (!bundle.hasEntry() || bundle.getEntry().isEmpty()) {
      throw new IllegalArgumentException("Bundle must have at least one entry");
    }
    Resource firstResource = bundle.getEntryFirstRep().getResource();
    if (!(firstResource instanceof Claim claim)) {
      throw new IllegalArgumentException(
          "First bundle entry must be a Claim, got: "
              + (firstResource != null ? firstResource.fhirType() : "null"));
    }
    if (claim.getUse() != Claim.Use.PREAUTHORIZATION) {
      throw new IllegalArgumentException(
          "Claim.use must be 'preauthorization', got: " + claim.getUse());
    }
    if (!claim.hasPatient()) {
      throw new IllegalArgumentException("Claim.patient is required");
    }
    if (!claim.hasInsurer()) {
      throw new IllegalArgumentException("Claim.insurer is required");
    }
    if (!claim.hasProvider()) {
      throw new IllegalArgumentException("Claim.provider is required");
    }
    if (!claim.hasInsurance() || claim.getInsurance().isEmpty()) {
      throw new IllegalArgumentException(
          "Claim.insurance is required with at least one coverage");
    }
    return claim;
  }

  /**
   * Searches all bundle entries for a Patient resource that has at least one identifier
   * with type coding code="MB" from the HL7 v2-0203 code system.
   *
   * @throws IllegalArgumentException if no Patient with an MB identifier is found
   */
  private void validateMemberIdentifier(Bundle bundle) {
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      Resource resource = entry.getResource();
      if (!(resource instanceof Patient patient)) {
        continue;
      }
      for (Identifier identifier : patient.getIdentifier()) {
        if (identifier.hasType()) {
          for (Coding coding : identifier.getType().getCoding()) {
            if (FhirConstants.IDENTIFIER_TYPE_SYSTEM.equals(coding.getSystem())
                && FhirConstants.MB_TYPE_CODE.equals(coding.getCode())) {
              return;
            }
          }
        }
      }
    }
    throw new IllegalArgumentException(
        "Patient member identifier (type=MB) is required for inquiry");
  }
}
