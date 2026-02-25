package org.hl7.davinci.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hl7.davinci.pas.PasSubmitService;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;

class ClaimSubmitProviderTest {

  @Test
  void claimSubmit_delegatesToPasSubmitService() {
    PasSubmitService submitService = mock(PasSubmitService.class);
    ClaimSubmitProvider provider = new ClaimSubmitProvider(submitService);
    Bundle request = new Bundle();
    Bundle expected = new Bundle();
    expected.setId("response-bundle");
    when(submitService.submit(request)).thenReturn(expected);

    Bundle response = provider.claimSubmit(request);

    assertEquals("response-bundle", response.getIdElement().getIdPart());
  }

  @Test
  void claimSubmit_translatesIllegalArgumentExceptionToInvalidRequestException() {
    PasSubmitService submitService = mock(PasSubmitService.class);
    ClaimSubmitProvider provider = new ClaimSubmitProvider(submitService);
    Bundle request = new Bundle();
    when(submitService.submit(request)).thenThrow(new IllegalArgumentException("Invalid PAS bundle"));

    InvalidRequestException exception = assertThrows(
        InvalidRequestException.class,
        () -> provider.claimSubmit(request));

    assertTrue(exception.getMessage().contains("Invalid PAS bundle"));
    assertEquals(400, exception.getStatusCode());
  }
}
