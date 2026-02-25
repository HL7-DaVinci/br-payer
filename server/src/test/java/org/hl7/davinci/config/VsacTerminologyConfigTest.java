package org.hl7.davinci.config;

import static org.hl7.davinci.common.FhirConstants.VSAC_VALUESET_PREFIX;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import ca.uhn.fhir.context.support.ValueSetExpansionOptions;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

class VsacTerminologyConfigTest {

  @Test
  void isValueSetSupported_onlySupportsVsacPrefix() {
    IGenericClient client = mock(IGenericClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    var support = new VsacTerminologyConfig.VsacValidationSupport(FhirContext.forR4Cached(), client);

    assertTrue(support.isValueSetSupported(null, VSAC_VALUESET_PREFIX + "2.16.840.1.113762.1.4.1219.132"));
    assertTrue(!support.isValueSetSupported(null, "http://example.org/ValueSet/local"));
  }

  @Test
  void fetchValueSet_returnsNullForUnsupportedOrNotFoundValuesets() {
    IGenericClient client = mock(IGenericClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(client.read().resource(ValueSet.class).withId("2.16.840.1.113762.1.4.1219.132").execute())
        .thenThrow(new ResourceNotFoundException("not found"));

    var support = new VsacTerminologyConfig.VsacValidationSupport(FhirContext.forR4Cached(), client);

    assertNull(support.fetchValueSet("http://example.org/ValueSet/local"));
    assertNull(support.fetchValueSet(VSAC_VALUESET_PREFIX + "2.16.840.1.113762.1.4.1219.132"));
  }

  @Test
  void expandValueSet_returnsOutcomeWhenVsacClientReturnsExpandedValueSet() {
    IGenericClient client = mock(IGenericClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    ValueSet expanded = new ValueSet();
    expanded.setUrl(VSAC_VALUESET_PREFIX + "2.16.840.1.113762.1.4.1219.132");
    when(client.operation()
        .onType(ValueSet.class)
        .named("expand")
        .withParameter(
            eq(org.hl7.fhir.r4.model.Parameters.class),
            eq("url"),
            any(org.hl7.fhir.r4.model.UriType.class))
        .returnResourceType(ValueSet.class)
        .execute())
        .thenReturn(expanded);

    var support = new VsacTerminologyConfig.VsacValidationSupport(FhirContext.forR4Cached(), client);
    var outcome = support.expandValueSet(
        mock(ValidationSupportContext.class),
        new ValueSetExpansionOptions(),
        VSAC_VALUESET_PREFIX + "2.16.840.1.113762.1.4.1219.132");

    assertNotNull(outcome);
    assertNotNull(outcome.getValueSet());
  }
}
