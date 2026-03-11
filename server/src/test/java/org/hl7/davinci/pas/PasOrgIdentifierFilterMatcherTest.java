package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.*;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryMatchResult;
import ca.uhn.fhir.jpa.subscription.model.CanonicalTopicSubscriptionFilter;

class PasOrgIdentifierFilterMatcherTest {

  private PasOrgIdentifierFilterMatcher matcher;

  @BeforeEach
  void setUp() {
    matcher = new PasOrgIdentifierFilterMatcher();
  }

  @Test
  void match_returnsSuccessWhenReceiverCodeMatches() {
    ClaimResponse cr = buildClaimResponseWithReceiverCode("8189991234");
    CanonicalTopicSubscriptionFilter filter = buildFilter(
        PasConstants.FILTER_ORG_IDENTIFIER, "8189991234");

    InMemoryMatchResult result = matcher.match(filter, cr);

    assertTrue(result.matched());
  }

  @Test
  void match_returnsNoMatchWhenReceiverCodeDiffers() {
    ClaimResponse cr = buildClaimResponseWithReceiverCode("8189991234");
    CanonicalTopicSubscriptionFilter filter = buildFilter(
        PasConstants.FILTER_ORG_IDENTIFIER, "9999999999");

    InMemoryMatchResult result = matcher.match(filter, cr);

    assertFalse(result.matched());
  }

  @Test
  void match_returnsNoMatchWhenNoTransmissionIdentifiers() {
    ClaimResponse cr = new ClaimResponse();
    CanonicalTopicSubscriptionFilter filter = buildFilter(
        PasConstants.FILTER_ORG_IDENTIFIER, "8189991234");

    InMemoryMatchResult result = matcher.match(filter, cr);

    assertFalse(result.matched());
  }

  @Test
  void match_returnsUnsupportedForUnknownFilterParameter() {
    ClaimResponse cr = new ClaimResponse();
    CanonicalTopicSubscriptionFilter filter = buildFilter("unknownParam", "someValue");

    InMemoryMatchResult result = matcher.match(filter, cr);

    assertFalse(result.supported());
  }

  @Test
  void match_returnsNoMatchForNonClaimResponseResource() {
    Patient patient = new Patient();
    CanonicalTopicSubscriptionFilter filter = buildFilter(
        PasConstants.FILTER_ORG_IDENTIFIER, "8189991234");

    InMemoryMatchResult result = matcher.match(filter, patient);

    assertFalse(result.matched());
  }

  @Test
  void match_returnsNoMatchWhenFilterValueIsBlank() {
    ClaimResponse cr = buildClaimResponseWithReceiverCode("8189991234");
    CanonicalTopicSubscriptionFilter filter = buildFilter(
        PasConstants.FILTER_ORG_IDENTIFIER, "");

    InMemoryMatchResult result = matcher.match(filter, cr);

    assertFalse(result.matched());
  }

  @Test
  void match_returnsSuccessWhenFilterUsesTokenFormat() {
    ClaimResponse cr = buildClaimResponseWithReceiverCode("8189991234");
    CanonicalTopicSubscriptionFilter filter = buildFilter(
        PasConstants.FILTER_ORG_IDENTIFIER, "http://hl7.org/fhir/sid/us-npi|8189991234");

    InMemoryMatchResult result = matcher.match(filter, cr);

    assertTrue(result.matched());
  }

  @Test
  void match_returnsNoMatchWhenTokenValueDiffers() {
    ClaimResponse cr = buildClaimResponseWithReceiverCode("8189991234");
    CanonicalTopicSubscriptionFilter filter = buildFilter(
        PasConstants.FILTER_ORG_IDENTIFIER, "http://hl7.org/fhir/sid/us-npi|9999999999");

    InMemoryMatchResult result = matcher.match(filter, cr);

    assertFalse(result.matched());
  }

  @Test
  void match_returnsSuccessWhenBundleContainsMatchingClaimResponse() {
    ClaimResponse cr = buildClaimResponseWithReceiverCode("8189991234");
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.addEntry().setResource(cr);

    CanonicalTopicSubscriptionFilter filter = buildFilter(
        PasConstants.FILTER_ORG_IDENTIFIER, "8189991234");

    InMemoryMatchResult result = matcher.match(filter, bundle);

    assertTrue(result.matched());
  }

  @Test
  void match_returnsNoMatchWhenBundleContainsNonMatchingClaimResponse() {
    ClaimResponse cr = buildClaimResponseWithReceiverCode("9999999999");
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.addEntry().setResource(cr);

    CanonicalTopicSubscriptionFilter filter = buildFilter(
        PasConstants.FILTER_ORG_IDENTIFIER, "8189991234");

    InMemoryMatchResult result = matcher.match(filter, bundle);

    assertFalse(result.matched());
  }

  @Test
  void match_returnsNoMatchWhenBundleHasNoClaimResponse() {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.addEntry().setResource(new Patient());

    CanonicalTopicSubscriptionFilter filter = buildFilter(
        PasConstants.FILTER_ORG_IDENTIFIER, "8189991234");

    InMemoryMatchResult result = matcher.match(filter, bundle);

    assertFalse(result.matched());
  }

  private ClaimResponse buildClaimResponseWithReceiverCode(String receiverCode) {
    ClaimResponse cr = new ClaimResponse();
    cr.addExtension(PasExtensions.buildTransmissionIdentifiersExtension("PAYER", receiverCode));
    return cr;
  }

  private CanonicalTopicSubscriptionFilter buildFilter(String parameter, String value) {
    CanonicalTopicSubscriptionFilter filter = new CanonicalTopicSubscriptionFilter();
    filter.setFilterParameter(parameter);
    filter.setValue(value);
    return filter;
  }
}
