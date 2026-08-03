package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Subscription;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

class PasSubscriptionCriteriaInterceptorTest {

  private static final String FILTER_EXT =
      "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-filter-criteria";

  private Subscription subscriptionWithFilter(String filterValue) {
    Subscription sub = new Subscription();
    sub.setCriteria("http://hl7.org/fhir/us/davinci-pas/SubscriptionTopic/PASSubscriptionTopic");
    sub.getCriteriaElement().addExtension(FILTER_EXT, new StringType(filterValue));
    return sub;
  }

  @Test
  void bareIgFormIsNormalizedToHapiParseableForm() {
    Subscription sub = subscriptionWithFilter("org-identifier=1234567893");
    new PasSubscriptionCriteriaInterceptor().normalize(sub);
    assertEquals("Bundle?org-identifier=1234567893",
        ((StringType) sub.getCriteriaElement().getExtensionByUrl(FILTER_EXT).getValue()).getValue());
  }

  @Test
  void prefixedFormPassesThroughUnchanged() {
    Subscription sub = subscriptionWithFilter("Bundle?org-identifier=1234567893");
    new PasSubscriptionCriteriaInterceptor().normalize(sub);
    assertEquals("Bundle?org-identifier=1234567893",
        ((StringType) sub.getCriteriaElement().getExtensionByUrl(FILTER_EXT).getValue()).getValue());
  }

  @Test
  void multiValueFormIsAccepted() {
    Subscription sub = subscriptionWithFilter("org-identifier=N123456,4543315");
    new PasSubscriptionCriteriaInterceptor().normalize(sub);
    assertEquals("Bundle?org-identifier=N123456,4543315",
        ((StringType) sub.getCriteriaElement().getExtensionByUrl(FILTER_EXT).getValue()).getValue());
  }

  @Test
  void unrecognizedCriteriaIsRejected() {
    Subscription sub = subscriptionWithFilter("patient=Patient/123");
    assertThrows(UnprocessableEntityException.class,
        () -> new PasSubscriptionCriteriaInterceptor().normalize(sub));
  }

  @Test
  void missingFilterIsRejected() {
    Subscription sub = new Subscription();
    sub.setCriteria("http://hl7.org/fhir/us/davinci-pas/SubscriptionTopic/PASSubscriptionTopic");
    assertThrows(UnprocessableEntityException.class,
        () -> new PasSubscriptionCriteriaInterceptor().normalize(sub));
  }

  @Test
  void nonStringExtensionValueIsRejected() {
    Subscription sub = new Subscription();
    sub.setCriteria("http://hl7.org/fhir/us/davinci-pas/SubscriptionTopic/PASSubscriptionTopic");
    sub.getCriteriaElement().addExtension(FILTER_EXT, new BooleanType(true));
    assertThrows(UnprocessableEntityException.class,
        () -> new PasSubscriptionCriteriaInterceptor().normalize(sub));
  }
}
