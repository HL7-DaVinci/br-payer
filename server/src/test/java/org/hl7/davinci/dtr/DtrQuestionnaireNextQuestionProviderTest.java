package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import org.hl7.davinci.providers.DtrQuestionnaireNextQuestionProvider;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DtrQuestionnaireNextQuestionProviderTest {

  @Mock
  private AdaptiveNextQuestionService nextQuestionService;

  @Test
  @DisplayName("Operation metadata: correct name, type, and canonical URL")
  void operationMetadata() throws NoSuchMethodException {
    Method method = DtrQuestionnaireNextQuestionProvider.class
        .getMethod("dtrQuestionnaireNextQuestion", QuestionnaireResponse.class);

    Operation opAnnotation = method.getAnnotation(Operation.class);
    assertNotNull(opAnnotation, "Method should have @Operation annotation");
    assertEquals("$next-question", opAnnotation.name());
    assertEquals(Questionnaire.class, opAnnotation.type());
    assertEquals(
        "http://hl7.org/fhir/us/davinci-dtr/OperationDefinition/DTR-Questionnaire-next-question",
        opAnnotation.canonicalUrl());
  }

  @Test
  @DisplayName("Input parameter name is 'questionnaire-response'")
  void inputParameterName() throws NoSuchMethodException {
    Method method = DtrQuestionnaireNextQuestionProvider.class
        .getMethod("dtrQuestionnaireNextQuestion", QuestionnaireResponse.class);

    var params = method.getParameters();
    assertEquals(1, params.length);
    OperationParam paramAnnotation = params[0].getAnnotation(OperationParam.class);
    assertNotNull(paramAnnotation, "Parameter should have @OperationParam annotation");
    assertEquals("questionnaire-response", paramAnnotation.name());
    assertEquals(QuestionnaireResponse.class, paramAnnotation.type());
  }

  @Test
  @DisplayName("Return type is Parameters")
  void returnType() throws NoSuchMethodException {
    Method method = DtrQuestionnaireNextQuestionProvider.class
        .getMethod("dtrQuestionnaireNextQuestion", QuestionnaireResponse.class);

    assertEquals(Parameters.class, method.getReturnType());
  }

  @Test
  @DisplayName("Null input throws InvalidRequestException")
  void nullInput_throwsInvalidRequest() {
    DtrQuestionnaireNextQuestionProvider provider =
        new DtrQuestionnaireNextQuestionProvider(nextQuestionService);

    assertThrows(InvalidRequestException.class,
        () -> provider.dtrQuestionnaireNextQuestion(null));
  }

  @Test
  @DisplayName("Delegates to AdaptiveNextQuestionService")
  void delegatesToService() {
    DtrQuestionnaireNextQuestionProvider provider =
        new DtrQuestionnaireNextQuestionProvider(nextQuestionService);

    QuestionnaireResponse qr = new QuestionnaireResponse();
    qr.setId("test-id");

    Parameters expected = new Parameters();
    when(nextQuestionService.processNextQuestion(qr)).thenReturn(expected);

    Parameters result = provider.dtrQuestionnaireNextQuestion(qr);

    assertSame(expected, result);
    verify(nextQuestionService).processNextQuestion(qr);
  }
}
