package org.hl7.davinci.pas;

import static org.hl7.davinci.common.FhirConstants.*;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.hl7.davinci.common.ResourceResolver;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
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
  private final PasBundleReferenceResolver bundleReferenceResolver;

  public PasInquiryService(PasBundleValidator validator, DaoRegistry daoRegistry,
      PasResponseBuilder responseBuilder, PasBundleReferenceResolver bundleReferenceResolver) {
    this.validator = validator;
    this.daoRegistry = daoRegistry;
    this.responseBuilder = responseBuilder;
    this.bundleReferenceResolver = bundleReferenceResolver;
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
    bundleReferenceResolver.resolveInquiryReferences(requestBundle, claim);
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
    if (!hasReferenceNumberMatch(inquiryClaim, candidateResponse)) {
      return false;
    }
    if (!hasItemTraceNumberMatch(inquiryClaim, requestedClaim)) {
      return false;
    }
    return hasProductOrServiceMatch(inquiryClaim, requestedClaim);
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

  /**
   * Matches inquiry Claim extensions (authorizationNumber, administrationReferenceNumber)
   * against the candidate ClaimResponse's item-level auth/admin ref numbers.
   * If the inquiry has neither extension, the filter is skipped (matches by other criteria only).
   */
  private boolean hasReferenceNumberMatch(Claim inquiryClaim, ClaimResponse candidateResponse) {
    Set<String> inquiryAuthNumbers = extractClaimExtensionValues(inquiryClaim, PasConstants.AUTHORIZATION_NUMBER);
    Set<String> inquiryAdminRefs = extractClaimExtensionValues(inquiryClaim, PasConstants.ADMIN_REF_NUMBER);

    if (inquiryAuthNumbers.isEmpty() && inquiryAdminRefs.isEmpty()) {
      return true;
    }

    Set<String> crAuthNumbers = PasExtensions.extractAllAuthorizationNumbers(candidateResponse);
    Set<String> crAdminRefs = PasExtensions.extractAllAdminRefNumbers(candidateResponse);

    boolean authMatch = inquiryAuthNumbers.stream().anyMatch(crAuthNumbers::contains);
    boolean adminMatch = inquiryAdminRefs.stream().anyMatch(crAdminRefs::contains);
    return authMatch || adminMatch;
  }

  private Set<String> extractClaimExtensionValues(Claim claim, String extensionUrl) {
    Stream<Extension> claimLevelExtensions = claim.getExtensionsByUrl(extensionUrl).stream();
    Stream<Extension> itemLevelExtensions = claim.getItem().stream()
        .flatMap(item -> item.getExtensionsByUrl(extensionUrl).stream());

    return Stream.concat(claimLevelExtensions, itemLevelExtensions)
        .map(Extension::getValue)
        .filter(v -> v instanceof StringType)
        .map(v -> ((StringType) v).getValue())
        .filter(v -> v != null && !v.isBlank())
        .collect(Collectors.toSet());
  }

  /**
   * When the inquiry Claim items carry itemTraceNumber extensions, at least one stored Claim item
   * must share a matching trace number. This prevents seed-data or unrelated prior-authorization
   * ClaimResponses from polluting inquiry results when the inquiry uses no Claim.identifier.
   * If the inquiry has no item trace numbers, this check is skipped (returns true).
   */
  private boolean hasItemTraceNumberMatch(Claim inquiryClaim, Claim storedClaim) {
    Set<String> inquiryTraceNumbers = extractItemTraceNumbers(inquiryClaim);
    if (inquiryTraceNumbers.isEmpty()) {
      return true;
    }
    Set<String> storedTraceNumbers = extractItemTraceNumbers(storedClaim);
    return inquiryTraceNumbers.stream().anyMatch(storedTraceNumbers::contains);
  }

  private Set<String> extractItemTraceNumbers(Claim claim) {
    return claim.getItem().stream()
        .flatMap(item -> item.getExtensionsByUrl(PasConstants.ITEM_TRACE_NUMBER).stream())
        .map(Extension::getValue)
        .filter(v -> v instanceof Identifier)
        .map(v -> ((Identifier) v).getValue())
        .filter(Objects::nonNull)
        .filter(v -> !v.isBlank())
        .collect(Collectors.toSet());
  }

  private static final String NOT_APPLICABLE_CODE = "not-applicable";

  /**
   * Query-by-example productOrService matching per PAS IG spec-43.
   * If the inquiry Claim items have specific productOrService codes, at least one
   * must match a code on the stored Claim's items. If all inquiry items use the
   * "not-applicable" code or have no items, the filter is skipped (wildcard).
   */
  private boolean hasProductOrServiceMatch(Claim inquiryClaim, Claim storedClaim) {
    Set<String> inquiryCodes = extractProductOrServiceCodes(inquiryClaim);
    if (inquiryCodes.isEmpty()) {
      return true;
    }
    Set<String> storedCodes = extractProductOrServiceCodes(storedClaim);
    return inquiryCodes.stream().anyMatch(storedCodes::contains);
  }

  /**
   * Extracts productOrService coding values as "system|code" strings, excluding
   * the "not-applicable" data-absent-reason code which acts as a wildcard.
   */
  private Set<String> extractProductOrServiceCodes(Claim claim) {
    return claim.getItem().stream()
        .filter(item -> item.hasProductOrService())
        .flatMap(item -> item.getProductOrService().getCoding().stream())
        .filter(coding -> coding.hasSystem() && coding.hasCode())
        .filter(coding -> !(DATA_ABSENT_REASON_SYSTEM.equals(coding.getSystem())
            && NOT_APPLICABLE_CODE.equals(coding.getCode())))
        .map(coding -> coding.getSystem() + "|" + coding.getCode())
        .collect(Collectors.toSet());
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
