package org.hl7.davinci.cdshooks.shared;

import java.util.List;

import org.hl7.davinci.common.ResourceResolver;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Medication;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.SupplyRequest;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;

/**
 * CDS Hooks wrapper around {@link org.hl7.davinci.common.FhirCodeExtractor}.
 * Resolves MedicationRequest medication references via the CDS prefetch/server before
 * delegating to the common code extractor.
 */
public final class FhirCodeExtractor {

  private FhirCodeExtractor() {
  }

  /**
   * Extracts codes from context resources, resolving item references
   * via the CDS request's prefetch and FHIR server.
   */
  public static List<Coding> extractCodes(Resource resource, boolean normalizeSystem, CdsServiceRequestJson request) {
    Resource resolved = org.hl7.davinci.common.FhirCodeExtractor.resolveReferencedItem(
        resource,
        itemRef -> {
          if (resource instanceof MedicationRequest medRequest) {
            return ResourceResolver.resolveReference(itemRef, Medication.class, medRequest, request);
          }
          if (resource instanceof SupplyRequest supplyRequest) {
            return ResourceResolver.resolveReference(itemRef, Resource.class, supplyRequest, request);
          }
          return null;
        });
    return org.hl7.davinci.common.FhirCodeExtractor.extractCodes(resource, normalizeSystem, resolved);
  }

  /**
   * @see org.hl7.davinci.common.FhirCodeExtractor#codeableConceptDisplay(CodeableConcept)
   */
  public static String codeableConceptDisplay(CodeableConcept codeableConcept) {
    return org.hl7.davinci.common.FhirCodeExtractor.codeableConceptDisplay(codeableConcept);
  }
}
