package org.hl7.davinci.pas;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.hl7.davinci.common.ResourceResolver;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

/**
 * Orchestrates the PAS $inquire workflow: validate -> search -> build response.
 * Queries stored ClaimResponse resources matching the inquiry criteria and returns
 * a Parameters resource containing one responseBundle per match.
 */
@Service
public class PasInquiryService {

  private final PasBundleValidator validator;
  private final DaoRegistry daoRegistry;
  private final PasResponseBuilder responseBuilder;

  public PasInquiryService(PasBundleValidator validator, DaoRegistry daoRegistry,
      PasResponseBuilder responseBuilder) {
    this.validator = validator;
    this.daoRegistry = daoRegistry;
    this.responseBuilder = responseBuilder;
  }

  /**
   * Processes a PAS $inquire request bundle and returns a Parameters resource
   * with one responseBundle parameter per matching prior authorization.
   *
   * @param requestBundle the PAS inquiry request bundle containing a Claim as first entry
   * @return Parameters with 0..* responseBundle parameters
   * @throws IllegalArgumentException if the request bundle is invalid
   */
  public Parameters inquire(Bundle requestBundle) {
    Claim claim = validator.validateInquiryBundle(requestBundle);
    List<ClaimResponse> matches = searchClaimResponses(claim);
    return responseBuilder.buildInquiryResponse(matches);
  }

  /**
   * Searches for stored ClaimResponse resources matching the inquiry Claim.
   * Always scopes the search using patient/insurer/requestor and then validates
   * request-level coverage and identifier context against the referenced Claim.
   */
  private List<ClaimResponse> searchClaimResponses(Claim claim) {
    Set<String> inquiryCoverageRefs = extractCoverageReferences(claim);
    if (inquiryCoverageRefs.isEmpty()) {
      throw new IllegalArgumentException("Claim.insurance.coverage is required for inquiry");
    }

    SearchParameterMap params = new SearchParameterMap();
    params.add("status", new TokenParam("active"));
    params.add("use", new TokenParam("preauthorization"));
    addRequiredReferenceParam(params, "patient", claim.getPatient(), "Patient", "Claim.patient");
    addRequiredReferenceParam(params, "insurer", claim.getInsurer(), "Organization", "Claim.insurer");
    addRequiredReferenceParam(
        params, "requestor", claim.getProvider(), "Organization|Practitioner|PractitionerRole",
        "Claim.provider");

    IBundleProvider results = daoRegistry.getResourceDao(ClaimResponse.class)
        .search(params, new SystemRequestDetails());

    return results.getResources(0, Integer.MAX_VALUE).stream()
        .filter(r -> r instanceof ClaimResponse)
        .map(r -> (ClaimResponse) r)
        .filter(cr -> matchesInquiryContext(claim, cr, inquiryCoverageRefs))
        .collect(Collectors.toList());
  }

  private boolean matchesInquiryContext(Claim inquiryClaim, ClaimResponse candidateResponse,
      Set<String> inquiryCoverageRefs) {
    Claim requestedClaim = readRequestedClaim(candidateResponse);
    if (requestedClaim == null) {
      return false;
    }
    if (!hasCoverageMatch(requestedClaim, inquiryCoverageRefs)) {
      return false;
    }
    return hasIdentifierMatch(inquiryClaim, requestedClaim);
  }

  private Claim readRequestedClaim(ClaimResponse claimResponse) {
    String requestRef = ResourceResolver.toVersionlessTypedReference(
        claimResponse.getRequest(), "Claim");
    if (requestRef == null) {
      return null;
    }

    String claimIdPart = ResourceResolver.normalizeReferenceId(requestRef, "Claim");
    if (claimIdPart == null || claimIdPart.isBlank()) {
      return null;
    }

    try {
      return daoRegistry.getResourceDao(Claim.class)
          .read(new IdType("Claim", claimIdPart), new SystemRequestDetails());
    } catch (ResourceNotFoundException e) {
      return null;
    }
  }

  private void addRequiredReferenceParam(SearchParameterMap params, String paramName,
      Reference reference, String expectedTypes, String fieldName) {
    String normalizedRef = normalizeAllowedTypedReference(reference, expectedTypes);
    if (normalizedRef == null) {
      throw new IllegalArgumentException(fieldName + " reference is required for inquiry");
    }
    params.add(paramName, new ReferenceParam(normalizedRef));
  }

  private Set<String> extractCoverageReferences(Claim claim) {
    return claim.getInsurance().stream()
        .map(Claim.InsuranceComponent::getCoverage)
        .map(ref -> ResourceResolver.toVersionlessTypedReference(ref, "Coverage"))
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  private boolean hasCoverageMatch(Claim requestedClaim, Set<String> inquiryCoverageRefs) {
    return extractCoverageReferences(requestedClaim).stream()
        .anyMatch(inquiryCoverageRefs::contains);
  }

  private boolean hasIdentifierMatch(Claim inquiryClaim, Claim requestedClaim) {
    List<Identifier> inquiryIds = inquiryClaim.getIdentifier().stream()
        .filter(id -> id.hasSystem() || id.hasValue())
        .collect(Collectors.toList());
    if (inquiryIds.isEmpty()) {
      return true;
    }
    return inquiryIds.stream().anyMatch(inquiryId -> requestedClaim.getIdentifier().stream()
        .anyMatch(storedId -> matchesIdentifier(storedId, inquiryId)));
  }

  private boolean matchesIdentifier(Identifier storedId, Identifier inquiryId) {
    if (inquiryId.hasSystem() && !inquiryId.getSystem().equals(storedId.getSystem())) {
      return false;
    }
    if (inquiryId.hasValue() && !inquiryId.getValue().equals(storedId.getValue())) {
      return false;
    }
    return true;
  }

  private String normalizeAllowedTypedReference(Reference reference, String expectedTypes) {
    if (reference == null || !reference.hasReference()
        || expectedTypes == null || expectedTypes.isBlank()) {
      return null;
    }

    for (String expectedType : expectedTypes.split("\\|")) {
      String normalized = ResourceResolver.toVersionlessTypedReference(reference, expectedType);
      if (normalized != null && !normalized.isBlank()) {
        return normalized;
      }
    }
    return null;
  }
}
