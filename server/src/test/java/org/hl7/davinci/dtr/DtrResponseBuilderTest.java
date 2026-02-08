package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DtrResponseBuilderTest {

  private static final String QR_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaireresponse";
  private static final String QR_COVERAGE_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-coverage";
  private static final String INTENDED_USE_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/intendedUse";
  private static final String QR_CONTEXT_EXT =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-context";

  private DtrResponseBuilder builder;
  private Questionnaire testQ;
  private Coverage testCoverage;

  @BeforeEach
  void setUp() {
    builder = new DtrResponseBuilder();

    testQ = new Questionnaire();
    testQ.setId("q-1");
    testQ.setUrl("http://example.org/Questionnaire/test");
    testQ.setVersion("1.0");

    testCoverage = new Coverage();
    testCoverage.setId("cov-1");
    testCoverage.setBeneficiary(new Reference("Patient/pat-1"));
  }

  @Test
  @DisplayName("QR has correct profile, status, and version-specific questionnaire canonical")
  void basicQrFields() {
    DtrQuestionnaireResolver.ResolvedQuestionnaire provenance =
        new DtrQuestionnaireResolver.ResolvedQuestionnaire(
            "http://example.org/Questionnaire/test|1.0", testQ,
            DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE, new ArrayList<>(), null);

    QuestionnaireResponse qr = builder.buildResponse(testQ, testCoverage, provenance, List.of());

    assertTrue(qr.getMeta().hasProfile(QR_PROFILE));
    assertEquals(QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS, qr.getStatus());
    assertEquals("http://example.org/Questionnaire/test|1.0", qr.getQuestionnaire());
    assertNotNull(qr.getAuthored());
  }

  @Test
  @DisplayName("Subject set from Coverage beneficiary")
  void subjectFromCoverage() {
    DtrQuestionnaireResolver.ResolvedQuestionnaire provenance =
        new DtrQuestionnaireResolver.ResolvedQuestionnaire(
            "http://example.org/Questionnaire/test|1.0", testQ,
            DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE, new ArrayList<>(), null);

    QuestionnaireResponse qr = builder.buildResponse(testQ, testCoverage, provenance, List.of());

    assertNotNull(qr.getSubject());
    assertEquals("Patient/pat-1", qr.getSubject().getReference());
  }

  @Test
  @DisplayName("qr-coverage extension present with Coverage reference")
  void qrCoverageExtension() {
    DtrQuestionnaireResolver.ResolvedQuestionnaire provenance =
        new DtrQuestionnaireResolver.ResolvedQuestionnaire(
            "http://example.org/Questionnaire/test|1.0", testQ,
            DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE, new ArrayList<>(), null);

    QuestionnaireResponse qr = builder.buildResponse(testQ, testCoverage, provenance, List.of());

    Extension covExt = qr.getExtensionByUrl(QR_COVERAGE_EXT);
    assertNotNull(covExt);
    assertTrue(covExt.getValue() instanceof Reference);
    Reference coverageRef = (Reference) covExt.getValue();
    assertEquals("Coverage/cov-1", coverageRef.getReference());
  }

  @Test
  @DisplayName("intendedUse extension present with withorder code")
  void intendedUseExtension() {
    DtrQuestionnaireResolver.ResolvedQuestionnaire provenance =
        new DtrQuestionnaireResolver.ResolvedQuestionnaire(
            "http://example.org/Questionnaire/test|1.0", testQ,
            DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE, new ArrayList<>(), null);

    QuestionnaireResponse qr = builder.buildResponse(testQ, testCoverage, provenance, List.of());

    Extension intendedUse = qr.getExtensionByUrl(INTENDED_USE_EXT);
    assertNotNull(intendedUse);
  }

  @Test
  @DisplayName("QUESTIONNAIRE: all orders get qr-context")
  void questionnaire_allOrders() {
    DeviceRequest order1 = new DeviceRequest();
    order1.setId("dr-1");
    ServiceRequest order2 = new ServiceRequest();
    order2.setId("sr-1");
    List<Resource> orders = List.of(order1, order2);

    DtrQuestionnaireResolver.ResolvedQuestionnaire provenance =
        new DtrQuestionnaireResolver.ResolvedQuestionnaire(
            "http://example.org/Questionnaire/test|1.0", testQ,
            DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE, new ArrayList<>(), null);

    QuestionnaireResponse qr = builder.buildResponse(testQ, testCoverage, provenance, orders);

    List<Extension> contextExts = qr.getExtensionsByUrl(QR_CONTEXT_EXT);
    assertEquals(2, contextExts.size());
    List<String> contextRefs = contextExts.stream()
        .map(Extension::getValue)
        .filter(Reference.class::isInstance)
        .map(Reference.class::cast)
        .map(Reference::getReference)
        .toList();
    assertTrue(contextRefs.contains("DeviceRequest/dr-1"));
    assertTrue(contextRefs.contains("ServiceRequest/sr-1"));
  }

  @Test
  @DisplayName("ORDER: only source orders get qr-context")
  void order_sourceOrdersOnly() {
    DeviceRequest order1 = new DeviceRequest();
    order1.setId("DeviceRequest/dr-1");
    ServiceRequest order2 = new ServiceRequest();
    order2.setId("ServiceRequest/sr-1");
    List<Resource> orders = List.of(order1, order2);

    List<String> sourceOrderIds = new ArrayList<>();
    sourceOrderIds.add("DeviceRequest/dr-1");

    DtrQuestionnaireResolver.ResolvedQuestionnaire provenance =
        new DtrQuestionnaireResolver.ResolvedQuestionnaire(
            "http://example.org/Questionnaire/test|1.0", testQ,
            DtrQuestionnaireResolver.ResolutionPath.ORDER, sourceOrderIds, null);

    QuestionnaireResponse qr = builder.buildResponse(testQ, testCoverage, provenance, orders);

    List<Extension> contextExts = qr.getExtensionsByUrl(QR_CONTEXT_EXT);
    assertEquals(1, contextExts.size());
    assertTrue(contextExts.get(0).getValue() instanceof Reference);
    Reference contextRef = (Reference) contextExts.get(0).getValue();
    assertEquals("DeviceRequest/dr-1", contextRef.getReference());
  }

  @Test
  @DisplayName("BOTH: all orders get qr-context")
  void both_allOrders() {
    DeviceRequest order1 = new DeviceRequest();
    order1.setId("dr-1");
    ServiceRequest order2 = new ServiceRequest();
    order2.setId("sr-1");
    List<Resource> orders = List.of(order1, order2);

    List<String> sourceOrderIds = new ArrayList<>();
    sourceOrderIds.add("DeviceRequest/dr-1");

    DtrQuestionnaireResolver.ResolvedQuestionnaire provenance =
        new DtrQuestionnaireResolver.ResolvedQuestionnaire(
            "http://example.org/Questionnaire/test|1.0", testQ,
            DtrQuestionnaireResolver.ResolutionPath.BOTH, sourceOrderIds, null);

    QuestionnaireResponse qr = builder.buildResponse(testQ, testCoverage, provenance, orders);

    List<Extension> contextExts = qr.getExtensionsByUrl(QR_CONTEXT_EXT);
    assertEquals(2, contextExts.size());
  }
}
