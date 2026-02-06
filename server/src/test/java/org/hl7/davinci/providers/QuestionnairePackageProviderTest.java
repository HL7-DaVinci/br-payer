package org.hl7.davinci.providers;

import org.hl7.davinci.cdshooks.CdsHooksTestUtils;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;

import static org.junit.jupiter.api.Assertions.*;

class QuestionnairePackageProviderTest {

  private QuestionnairePackageProvider provider;
  private Coverage testCoverage;

  @BeforeEach
  void setUp() {
    provider = new QuestionnairePackageProvider();
    testCoverage = CdsHooksTestUtils.createTestCoverage("cov-1", "org-1");
  }

  @Nested
  @DisplayName("Valid requests (200)")
  class ValidRequestTests {

    @Test
    @DisplayName("Canonical-only request returns empty Parameters")
    void canonicalOnly_returns200() {
      List<CanonicalType> questionnaires = List.of(
          new CanonicalType("http://example.org/Questionnaire/test"));

      Parameters result = provider.questionnairePackage(
          testCoverage, null, questionnaires, null, null);

      assertNotNull(result);
    }

    @Test
    @DisplayName("Order-only request returns empty Parameters")
    void orderOnly_returns200() {
      DeviceRequest order = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient-1");
      List<IAnyResource> orders = List.of(order);

      Parameters result = provider.questionnairePackage(
          testCoverage, orders, null, null, null);

      assertNotNull(result);
    }

    @Test
    @DisplayName("Combined order and questionnaire request returns empty Parameters")
    void combined_returns200() {
      DeviceRequest order = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient-1");
      List<IAnyResource> orders = List.of(order);
      List<CanonicalType> questionnaires = List.of(
          new CanonicalType("http://example.org/Questionnaire/test"));

      Parameters result = provider.questionnairePackage(
          testCoverage, orders, questionnaires, null, null);

      assertNotNull(result);
    }

    @Test
    @DisplayName("Context alongside questionnaire is accepted")
    void contextWithQuestionnaire_returns200() {
      List<CanonicalType> questionnaires = List.of(
          new CanonicalType("http://example.org/Questionnaire/test"));

      Parameters result = provider.questionnairePackage(
          testCoverage, null, questionnaires, new StringType("some-context"), null);

      assertNotNull(result);
      // Should have warning about context being ignored
      assertTrue(result.hasParameter("outcome"));
    }

    @Test
    @DisplayName("changedsince parameter is accepted")
    void changedsince_accepted() {
      List<CanonicalType> questionnaires = List.of(
          new CanonicalType("http://example.org/Questionnaire/test"));

      Parameters result = provider.questionnairePackage(
          testCoverage, null, questionnaires, null, new InstantType("2026-01-01T00:00:00Z"));

      assertNotNull(result);
    }
  }

  @Nested
  @DisplayName("Error responses (400)")
  class ErrorResponseTests {

    @Test
    @DisplayName("Missing coverage throws 400")
    void missingCoverage_throws400() {
      List<CanonicalType> questionnaires = List.of(
          new CanonicalType("http://example.org/Questionnaire/test"));

      InvalidRequestException ex = assertThrows(InvalidRequestException.class,
          () -> provider.questionnairePackage(null, null, questionnaires, null, null));

      assertNotNull(ex.getMessage());
      assertTrue(ex.getMessage().contains("coverage"));
    }

    @Test
    @DisplayName("No questionnaire/order/context throws 400 (oper-6)")
    void noDiscoveryParams_throws400() {
      InvalidRequestException ex = assertThrows(InvalidRequestException.class,
          () -> provider.questionnairePackage(testCoverage, null, null, null, null));

      assertTrue(ex.getMessage().contains("oper-6"));
    }

    @Test
    @DisplayName("Context-only throws 400 not-supported")
    void contextOnly_throws400() {
      InvalidRequestException ex = assertThrows(InvalidRequestException.class,
          () -> provider.questionnairePackage(
              testCoverage, null, null, new StringType("some-context"), null));

      assertTrue(ex.getMessage().contains("not supported"));
    }

    @Test
    @DisplayName("All unsupported order types with no questionnaires throws 400")
    void allUnsupportedOrders_noQuestionnaires_throws400() {
      DocumentReference doc = new DocumentReference();
      doc.setId("doc-1");
      List<IAnyResource> orders = List.of(doc);

      InvalidRequestException ex = assertThrows(InvalidRequestException.class,
          () -> provider.questionnairePackage(testCoverage, orders, null, null, null));

      assertTrue(ex.getMessage().contains("Unsupported order type"));
    }
  }

  @Nested
  @DisplayName("Partial success with warnings")
  class PartialSuccessTests {

    @Test
    @DisplayName("Unsupported order type mixed with valid returns 200 with warnings")
    void mixedOrderTypes_returns200WithWarnings() {
      DeviceRequest validOrder = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient-1");
      DocumentReference unsupportedOrder = new DocumentReference();
      unsupportedOrder.setId("doc-1");
      List<IAnyResource> orders = List.of(validOrder, unsupportedOrder);

      Parameters result = provider.questionnairePackage(
          testCoverage, orders, null, null, null);

      assertNotNull(result);
      assertTrue(result.hasParameter("outcome"));
      OperationOutcome outcome = (OperationOutcome) result.getParameter("outcome").getResource();
      assertTrue(outcome.hasIssue());
      assertEquals(OperationOutcome.IssueSeverity.WARNING, outcome.getIssueFirstRep().getSeverity());
      assertTrue(outcome.getIssueFirstRep().getDiagnostics().contains("DocumentReference"));
    }

    @Test
    @DisplayName("All unsupported orders with questionnaires returns 200 with warnings")
    void allUnsupportedOrders_withQuestionnaires_returns200() {
      DocumentReference doc = new DocumentReference();
      doc.setId("doc-1");
      List<IAnyResource> orders = List.of(doc);
      List<CanonicalType> questionnaires = List.of(
          new CanonicalType("http://example.org/Questionnaire/test"));

      Parameters result = provider.questionnairePackage(
          testCoverage, orders, questionnaires, null, null);

      assertNotNull(result);
      assertTrue(result.hasParameter("outcome"));
    }
  }
}
