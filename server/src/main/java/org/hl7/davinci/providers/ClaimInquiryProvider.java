package org.hl7.davinci.providers;


import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.exceptions.NotImplementedOperationException;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

import org.hl7.davinci.common.BaseProvider;

@Component
public class ClaimInquiryProvider extends BaseProvider {
  @Operation(
    name = "$inquire",
    type = Claim.class,
    canonicalUrl = "http://hl7.org/fhir/us/davinci-pas/OperationDefinition/Claim-inquiry"
  )
  public Bundle claimInquiry(
    @OperationParam(name = "resource", min = 1, max = 1, type = Bundle.class) Bundle theResource
  ) {
    // TODO: Implement operation $inquire
    throw new NotImplementedOperationException("Operation $inquire is not implemented");
    
    // Bundle retVal = new Bundle();
    // return retVal;
    
  }

  
}