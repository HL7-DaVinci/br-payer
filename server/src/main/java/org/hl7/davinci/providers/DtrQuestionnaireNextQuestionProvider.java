package org.hl7.davinci.providers;


import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.exceptions.NotImplementedOperationException;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

import org.hl7.davinci.common.BaseProvider;

@Component
public class DtrQuestionnaireNextQuestionProvider extends BaseProvider {
  @Operation(
    name = "$next-question",
    type = Questionnaire.class,
    canonicalUrl = "http://hl7.org/fhir/us/davinci-dtr/OperationDefinition/DTR-Questionnaire-next-question"
  )
  public IAnyResource dtrQuestionnaireNextQuestion(
    @OperationParam(name = "questionnaire-response-in", min = 1, max = 1, type = IAnyResource.class) IAnyResource theQuestionnaireResponseIn
  ) {
    // TODO: Implement operation $next-question
    throw new NotImplementedOperationException("Operation $next-question is not implemented");
    
    // IAnyResource retVal = new IAnyResource();
    // return retVal;
    
  }

  
}