package org.hl7.davinci.providers;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.rest.server.exceptions.NotImplementedOperationException;
import jakarta.servlet.http.HttpServletResponse;

class LogQuestionnaireErrorsProviderTest {

  @Test
  void logQuestionnaireErrors_alwaysThrowsNotImplemented() {
    LogQuestionnaireErrorsProvider provider = new LogQuestionnaireErrorsProvider();

    NotImplementedOperationException exception = assertThrows(
        NotImplementedOperationException.class,
        () -> provider.logQuestionnaireErrors(
            new CanonicalType("http://example.org/Questionnaire/test"),
            new OperationOutcome(),
            mock(HttpServletResponse.class)));

    assertTrue(exception.getMessage().contains("not implemented"));
  }
}
