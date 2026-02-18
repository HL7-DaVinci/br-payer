package org.hl7.davinci.providers;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import org.hl7.davinci.cdshooks.error.OperationOutcomeBuilder;
import org.hl7.davinci.common.BaseProvider;
import org.hl7.davinci.pas.PasSubmitService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.springframework.stereotype.Component;

@Component
public class ClaimSubmitProvider extends BaseProvider {

  private final PasSubmitService submitService;

  public ClaimSubmitProvider(PasSubmitService submitService) {
    this.submitService = submitService;
  }

  @Operation(
    name = "$submit",
    type = Claim.class,
    canonicalUrl = "http://hl7.org/fhir/us/davinci-pas/OperationDefinition/Claim-submit"
  )
  public Bundle claimSubmit(
    @OperationParam(name = "resource", min = 1, max = 1, type = Bundle.class) Bundle theResource
  ) {
    try {
      return submitService.submit(theResource);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException(e.getMessage(),
          OperationOutcomeBuilder.createBadRequestOutcome(e.getMessage()));
    }
  }
}