package org.hl7.davinci.providers;


import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.exceptions.NotImplementedOperationException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

import org.hl7.davinci.common.BaseProvider;

@Component
public class LogQuestionnaireErrorsProvider extends BaseProvider {
  @Operation(
    name = "$log-questionnaire-errors",
    type = Questionnaire.class,
    manualResponse = true,
    canonicalUrl = "http://hl7.org/fhir/us/davinci-dtr/OperationDefinition/log-questionnaire-errors"
  )
  public void logQuestionnaireErrors(
    @OperationParam(name = "Questionnaire", min = 1, max = 1, type = CanonicalType.class) CanonicalType theQuestionnaire,
    @OperationParam(name = "OperationOutcome", min = 1, max = 1, type = OperationOutcome.class) OperationOutcome theOperationOutcome,
    HttpServletResponse theServletResponse
  ) throws IOException {
    // TODO: Implement operation $log-questionnaire-errors
    throw new NotImplementedOperationException("Operation $log-questionnaire-errors is not implemented");
    
  }

  
}