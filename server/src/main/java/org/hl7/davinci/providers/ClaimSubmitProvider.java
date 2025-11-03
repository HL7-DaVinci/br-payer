package org.hl7.davinci.providers;


import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.exceptions.NotImplementedOperationException;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

import org.hl7.davinci.common.BaseProvider;

@Component
public class ClaimSubmitProvider extends BaseProvider {
  @Operation(
    name = "$submit",
    type = Claim.class,
    canonicalUrl = "http://hl7.org/fhir/us/davinci-pas/OperationDefinition/Claim-submit"
  )
  public Bundle claimSubmit(
    @OperationParam(name = "resource", min = 1, max = 1, type = Bundle.class) Bundle theResource
  ) {
    // TODO: Implement operation $submit
    throw new NotImplementedOperationException("Operation $submit is not implemented");
    
    // Bundle retVal = new Bundle();
    // return retVal;
    
  }

  
}