package org.hl7.davinci.providers;

import org.hl7.davinci.cdshooks.CdsHooksTestUtils;
import org.hl7.davinci.common.OrderResourceTypes;
import org.hl7.davinci.dtr.DtrPackageService;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuestionnairePackageProviderTest {

  private QuestionnairePackageProvider provider;
  private DtrPackageService mockPackageService;
  private HttpServletRequest mockServletRequest;
  private Coverage testCoverage;

  @BeforeEach
  void setUp() {
    mockPackageService = mock(DtrPackageService.class);
    mockServletRequest = mock(HttpServletRequest.class);
    // Return empty Parameters by default
    when(mockPackageService.generatePackages(any(), any(), any(), any(), any()))
        .thenReturn(new Parameters());
    provider = new QuestionnairePackageProvider(mockPackageService, mockServletRequest);
    testCoverage = CdsHooksTestUtils.createTestCoverage("cov-1", "org-1");
  }

  @Nested
  @DisplayName("Valid requests (200)")
  class ValidRequestTests {

    @Test
    @DisplayName("Canonical-only request delegates to service")
    void canonicalOnly_returns200() {
      List<CanonicalType> questionnaires = List.of(
          new CanonicalType("http://example.org/Questionnaire/test"));

      Parameters result = provider.questionnairePackage(
          testCoverage, null, questionnaires, null, null);

      assertNotNull(result);
      verify(mockPackageService).generatePackages(eq(testCoverage), anyList(), eq(questionnaires), isNull(), isNull());
    }

    @Test
    @DisplayName("Order-only request delegates to service")
    void orderOnly_returns200() {
      DeviceRequest order = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient-1");
      List<IAnyResource> orders = List.of(order);

      Parameters result = provider.questionnairePackage(
          testCoverage, orders, null, null, null);

      assertNotNull(result);
      verify(mockPackageService).generatePackages(eq(testCoverage), anyList(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("All shared supported order types are accepted")
    void allSharedSupportedOrderTypes_areAccepted() {
      List<IAnyResource> orders = OrderResourceTypes.supportedTypes().stream()
          .map(QuestionnairePackageProviderTest::buildOrderResource)
          .map(IAnyResource.class::cast)
          .toList();

      Parameters result = provider.questionnairePackage(
          testCoverage, orders, null, null, null);

      assertNotNull(result);
      assertFalse(result.hasParameter("outcome"));
      verify(mockPackageService).generatePackages(
          eq(testCoverage),
          argThat(validOrders -> validOrders.size() == orders.size()),
          isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("Combined order and questionnaire request delegates to service")
    void combined_returns200() {
      DeviceRequest order = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient-1");
      List<IAnyResource> orders = List.of(order);
      List<CanonicalType> questionnaires = List.of(
          new CanonicalType("http://example.org/Questionnaire/test"));

      Parameters result = provider.questionnairePackage(
          testCoverage, orders, questionnaires, null, null);

      assertNotNull(result);
      verify(mockPackageService).generatePackages(eq(testCoverage), anyList(), eq(questionnaires), isNull(), isNull());
    }

    @Test
    @DisplayName("Context alongside questionnaire is accepted with warning")
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
      verify(mockPackageService).generatePackages(
          eq(testCoverage), anyList(), eq(questionnaires), any(InstantType.class), isNull());
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

  @Nested
  @DisplayName("Service delegation")
  class ServiceDelegationTests {

    @Test
    @DisplayName("Service result with packagebundle is returned to caller")
    void serviceResult_returned() {
      Parameters serviceResult = new Parameters();
      serviceResult.addParameter().setName("packagebundle").setResource(new Bundle());
      when(mockPackageService.generatePackages(any(), any(), any(), any(), any()))
          .thenReturn(serviceResult);

      List<CanonicalType> questionnaires = List.of(
          new CanonicalType("http://example.org/Questionnaire/test"));

      Parameters result = provider.questionnairePackage(
          testCoverage, null, questionnaires, null, null);

      assertTrue(result.hasParameter("packagebundle"));
    }

    @Test
    @DisplayName("Service warnings merged with provider warnings")
    void warningsMerged() {
      // Service returns result with its own outcome
      Parameters serviceResult = new Parameters();
      OperationOutcome serviceOutcome = new OperationOutcome();
      serviceOutcome.addIssue()
          .setSeverity(OperationOutcome.IssueSeverity.WARNING)
          .setCode(OperationOutcome.IssueType.INFORMATIONAL)
          .setDiagnostics("Service-level warning");
      serviceResult.addParameter().setName("outcome").setResource(serviceOutcome);
      when(mockPackageService.generatePackages(any(), any(), any(), any(), any()))
          .thenReturn(serviceResult);

      // Trigger a provider-level warning via context parameter
      List<CanonicalType> questionnaires = List.of(
          new CanonicalType("http://example.org/Questionnaire/test"));

      Parameters result = provider.questionnairePackage(
          testCoverage, null, questionnaires, new StringType("some-context"), null);

      OperationOutcome outcome = (OperationOutcome) result.getParameter("outcome").getResource();
      // Should have both service-level and provider-level warnings
      assertTrue(outcome.getIssue().size() >= 2);
    }
  }

  private static Resource buildOrderResource(String resourceType) {
    return switch (resourceType) {
      case "Appointment" -> new Appointment();
      case "CommunicationRequest" -> new CommunicationRequest();
      case "DeviceRequest" -> new DeviceRequest();
      case "Encounter" -> new Encounter();
      case "MedicationRequest" -> new MedicationRequest();
      case "NutritionOrder" -> new NutritionOrder();
      case "ServiceRequest" -> new ServiceRequest();
      case "SupplyRequest" -> new SupplyRequest();
      case "VisionPrescription" -> new VisionPrescription();
      default -> throw new IllegalArgumentException("Unsupported order type in test: " + resourceType);
    };
  }
}
