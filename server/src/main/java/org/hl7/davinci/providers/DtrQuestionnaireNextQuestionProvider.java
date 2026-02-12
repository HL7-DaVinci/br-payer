package org.hl7.davinci.providers;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

import org.hl7.davinci.common.BaseProvider;
import org.hl7.davinci.dtr.AdaptiveNextQuestionService;

@Component
public class DtrQuestionnaireNextQuestionProvider extends BaseProvider {

  private final AdaptiveNextQuestionService nextQuestionService;

  public DtrQuestionnaireNextQuestionProvider(AdaptiveNextQuestionService nextQuestionService) {
    this.nextQuestionService = nextQuestionService;
  }

  @Operation(
    name = "$next-question",
    type = Questionnaire.class,
    canonicalUrl = "http://hl7.org/fhir/us/davinci-dtr/OperationDefinition/DTR-Questionnaire-next-question"
  )
  public Parameters dtrQuestionnaireNextQuestion(
    @OperationParam(name = "questionnaire-response", min = 1, max = 1, type = QuestionnaireResponse.class)
    QuestionnaireResponse theQuestionnaireResponse
  ) {
    if (theQuestionnaireResponse == null) {
      throw new InvalidRequestException("questionnaire-response parameter is required");
    }
    return nextQuestionService.processNextQuestion(theQuestionnaireResponse);
  }
}
