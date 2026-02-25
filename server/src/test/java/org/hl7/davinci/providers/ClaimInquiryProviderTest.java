package org.hl7.davinci.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hl7.davinci.pas.PasInquiryService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Parameters;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;

class ClaimInquiryProviderTest {

  @Test
  void claimInquiry_delegatesToPasInquiryService() {
    PasInquiryService inquiryService = mock(PasInquiryService.class);
    ClaimInquiryProvider provider = new ClaimInquiryProvider(inquiryService);
    Bundle request = new Bundle();
    Parameters expected = new Parameters();
    expected.setId("params-1");
    when(inquiryService.inquire(request)).thenReturn(expected);

    Parameters response = provider.claimInquiry(request);

    assertEquals("params-1", response.getIdElement().getIdPart());
  }

  @Test
  void claimInquiry_translatesIllegalArgumentExceptionToInvalidRequestException() {
    PasInquiryService inquiryService = mock(PasInquiryService.class);
    ClaimInquiryProvider provider = new ClaimInquiryProvider(inquiryService);
    Bundle request = new Bundle();
    when(inquiryService.inquire(request)).thenThrow(new IllegalArgumentException("Invalid inquiry bundle"));

    InvalidRequestException exception = assertThrows(
        InvalidRequestException.class,
        () -> provider.claimInquiry(request));

    assertTrue(exception.getMessage().contains("Invalid inquiry bundle"));
    assertEquals(400, exception.getStatusCode());
  }
}
