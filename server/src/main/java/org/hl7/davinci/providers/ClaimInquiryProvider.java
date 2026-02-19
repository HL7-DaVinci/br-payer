package org.hl7.davinci.providers;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import org.hl7.davinci.cdshooks.error.OperationOutcomeBuilder;
import org.hl7.davinci.common.BaseProvider;
import org.hl7.davinci.pas.PasInquiryService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Parameters;
import org.springframework.stereotype.Component;

@Component
public class ClaimInquiryProvider extends BaseProvider {

  private final PasInquiryService inquiryService;

  public ClaimInquiryProvider(PasInquiryService inquiryService) {
    this.inquiryService = inquiryService;
  }

  @Operation(
    name = "$inquire",
    type = Claim.class,
    canonicalUrl = "http://hl7.org/fhir/us/davinci-pas/OperationDefinition/Claim-inquiry"
  )
  public Parameters claimInquiry(
    @OperationParam(name = "resource", min = 1, max = 1, type = Bundle.class) Bundle theResource
  ) {
    try {
      return inquiryService.inquire(theResource);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException(e.getMessage(),
          OperationOutcomeBuilder.createBadRequestOutcome(e.getMessage()));
    }
  }
}
