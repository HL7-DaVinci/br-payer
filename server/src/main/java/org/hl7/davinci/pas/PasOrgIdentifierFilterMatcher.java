package org.hl7.davinci.pas;

import java.util.Arrays;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryMatchResult;
import ca.uhn.fhir.jpa.subscription.model.CanonicalTopicSubscriptionFilter;
import ca.uhn.fhir.jpa.topic.filter.ISubscriptionTopicFilterMatcher;
import ca.uhn.fhir.rest.param.ParameterUtil;

/**
 * Matches PAS subscription filters against ClaimResponse resources.
 * Handles the {@code orgIdentifier} filter parameter defined by the PAS IG,
 * which filters by the applicationReceiverCode from the TransmissionIdentifiers extension.
 * The receiver on the ClaimResponse is the requesting provider organization.
 */
@Component
public class PasOrgIdentifierFilterMatcher implements ISubscriptionTopicFilterMatcher {

  private static final Logger log = LoggerFactory.getLogger(PasOrgIdentifierFilterMatcher.class);

  @Override
  public InMemoryMatchResult match(CanonicalTopicSubscriptionFilter filter, IBaseResource resource) {
    if (!PasConstants.FILTER_ORG_IDENTIFIER.equals(filter.getFilterParameter())) {
      return InMemoryMatchResult.unsupportedFromParameterAndReason(
          filter.getFilterParameter(),
          "Only orgIdentifier filter is supported for PAS subscriptions");
    }

    ClaimResponse cr = extractClaimResponse(resource);
    if (cr == null) {
      return InMemoryMatchResult.noMatch();
    }

    String providerCode = PasExtensions.extractApplicationReceiverCode(cr);
    if (providerCode == null) {
      log.debug("ClaimResponse {} has no applicationReceiverCode in TransmissionIdentifiers",
          cr.getIdElement().getIdPart());
      return InMemoryMatchResult.noMatch();
    }

    String filterValue = filter.getValue();
    if (filterValue == null || filterValue.isBlank()) {
      return InMemoryMatchResult.noMatch();
    }

    // spec-58 permits comma-separated values, e.g. "orgIdentifier=N123456,4543315";
    // each element may also use the FHIR token format (system|value) per PAS IG example:
    // "orgIdentifier=http://hl7.org/fhir/sid/us-npi|1234567893"
    String[] subscribedValues = filterValue.split(",");
    boolean matched = Arrays.stream(subscribedValues)
        .map(String::trim)
        .map(this::extractTokenValue)
        .anyMatch(providerCode::equals);

    if (matched) {
      return InMemoryMatchResult.successfulMatch();
    }

    return InMemoryMatchResult.noMatch();
  }

  /**
   * Extracts the value portion from a FHIR token filter value.
   * If the value contains a pipe (system|value), returns the part after it.
   * Otherwise returns the value as-is.
   */
  private String extractTokenValue(String filterValue) {
    int pipeIndex = ParameterUtil.nonEscapedIndexOf(filterValue, '|');
    if (pipeIndex >= 0 && pipeIndex < filterValue.length() - 1) {
      return filterValue.substring(pipeIndex + 1);
    }
    return filterValue;
  }

  private ClaimResponse extractClaimResponse(IBaseResource resource) {
    if (resource instanceof ClaimResponse cr) {
      return cr;
    }
    if (resource instanceof Bundle bundle) {
      return bundle.getEntry().stream()
          .map(Bundle.BundleEntryComponent::getResource)
          .filter(ClaimResponse.class::isInstance)
          .map(ClaimResponse.class::cast)
          .findFirst()
          .orElse(null);
    }
    return null;
  }
}
