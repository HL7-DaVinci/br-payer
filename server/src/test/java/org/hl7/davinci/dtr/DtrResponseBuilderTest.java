package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.UrlType;
import org.hl7.davinci.common.CrdConstants;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencds.cqf.fhir.cr.hapi.common.IQuestionnaireProcessorFactory;
import org.opencds.cqf.fhir.cr.questionnaire.QuestionnaireProcessor;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

class DtrResponseBuilderTest {

  private static final String QR_PROFILE = "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaireresponse";
  private static final String QR_ADAPT_PROFILE = "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaireresponse-adapt";
  private static final String QR_COVERAGE_EXT = "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-coverage";
  private static final String INTENDED_USE_EXT = "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/intendedUse";
  private static final String QR_CONTEXT_EXT = "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-context";
  private static final String INFO_ORIGIN_EXT = "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/information-origin";
  private static final String QUESTIONNAIRE_ADAPTIVE_EXT = "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-questionnaireAdaptive";

  private IQuestionnaireProcessorFactory mockFactory;
  private QuestionnaireProcessor mockProcessor;
  private DaoRegistry mockDaoRegistry;
  @SuppressWarnings("rawtypes")
  private IFhirResourceDao mockPatientDao;
  private AppProperties mockAppProperties;
  private DtrResponseBuilder builder;
  private Questionnaire testQ;
  private Coverage testCoverage;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    mockFactory = mock(IQuestionnaireProcessorFactory.class);
    mockProcessor = mock(QuestionnaireProcessor.class);
    when(mockFactory.create(any())).thenReturn(mockProcessor);

    mockDaoRegistry = mock(DaoRegistry.class);
    mockPatientDao = mock(IFhirResourceDao.class);
    when(mockDaoRegistry.getResourceDao(Patient.class)).thenReturn(mockPatientDao);
    when(mockPatientDao.read(any(), any())).thenThrow(new ResourceNotFoundException("Not found"));

    mockAppProperties = mock(AppProperties.class);
    when(mockAppProperties.getServer_address()).thenReturn("http://localhost:8080/fhir");

    builder = new DtrResponseBuilder(mockFactory, mockDaoRegistry,
        new DtrAdaptiveProperties("http://payer.example/fhir/Questionnaire/$next-question"),
        mockAppProperties);

    testQ = new Questionnaire();
    testQ.setId("q-1");
    testQ.setUrl("http://example.org/Questionnaire/test");
    testQ.setVersion("1.0");

    testCoverage = new Coverage();
    testCoverage.setId("cov-1");
    testCoverage.setBeneficiary(new Reference("Patient/pat-1"));
  }

  private DtrQuestionnaireResolver.ResolvedQuestionnaire questionnaireProvenance() {
    return new DtrQuestionnaireResolver.ResolvedQuestionnaire(
        "http://example.org/Questionnaire/test|1.0", testQ,
        DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE, new ArrayList<>(), null);
  }

  /**
   * Stubs the 6-arg populate() overload: populate(IBaseResource, String, List,
   * IBaseExtension, IBaseBundle, LibraryEngine)
   */
  private void stubPopulateReturnsNull() {
    when(mockProcessor.populate(
        any(IBaseResource.class), any(), any(), any(), any(), any()))
        .thenReturn(null);
  }

  private void stubPopulateThrows(RuntimeException ex) {
    when(mockProcessor.populate(
        any(IBaseResource.class), any(), any(), any(), any(), any()))
        .thenThrow(ex);
  }

  private void stubPopulateReturns(QuestionnaireResponse qr) {
    when(mockProcessor.populate(
        any(IBaseResource.class), any(), any(), any(), any(), any()))
        .thenReturn(qr);
  }

  /**
   * Builds a simple QuestionnaireResponse with two populated items (one with a
   * nested item) and one empty item, for testing information-origin extension
   * population and recursion.
   * 
   * @return
   */
  private QuestionnaireResponse buildPopulatedQr() {
    QuestionnaireResponse qr = new QuestionnaireResponse();
    QuestionnaireResponseItemComponent item = qr.addItem().setLinkId("1");
    item.addAnswer().setValue(new StringType("pre-populated value"));
    QuestionnaireResponseItemComponent nested = item.addItem().setLinkId("1.1");
    nested.addAnswer().setValue(new StringType("nested value"));
    qr.addItem().setLinkId("2");
    return qr;
  }

  @Nested
  @DisplayName("DTR Extension Enrichment")
  class DtrExtensionTests {

    @BeforeEach
    void stubPopulate() {
      stubPopulateReturnsNull();
    }

    @Test
    @DisplayName("QR has correct profile, status, and version-specific questionnaire canonical")
    void basicQrFields() {
      DtrResponseBuilder.PrepopulationResult result = builder.buildResponse(testQ, testCoverage,
          questionnaireProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      assertTrue(qr.getMeta().hasProfile(QR_PROFILE));
      assertEquals(QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS, qr.getStatus());
      assertEquals("http://example.org/Questionnaire/test|1.0", qr.getQuestionnaire());
      assertNotNull(qr.getAuthored());
    }

    @Test
    @DisplayName("Subject set from Coverage beneficiary")
    void subjectFromCoverage() {
      DtrResponseBuilder.PrepopulationResult result = builder.buildResponse(testQ, testCoverage,
          questionnaireProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      assertNotNull(qr.getSubject());
      assertEquals("Patient/pat-1", qr.getSubject().getReference());
    }

    @Test
    @DisplayName("qr-coverage extension present with Coverage reference")
    void qrCoverageExtension() {
      DtrResponseBuilder.PrepopulationResult result = builder.buildResponse(testQ, testCoverage,
          questionnaireProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      Extension covExt = qr.getExtensionByUrl(QR_COVERAGE_EXT);
      assertNotNull(covExt);
      assertTrue(covExt.getValue() instanceof Reference);
      Reference coverageRef = (Reference) covExt.getValue();
      assertEquals("Coverage/cov-1", coverageRef.getReference());
    }

    @Test
    @DisplayName("intendedUse extension uses CRD temp code system with DocReason-compatible code")
    void intendedUseExtension() {
      DtrResponseBuilder.PrepopulationResult result = builder.buildResponse(testQ, testCoverage,
          questionnaireProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      Extension intendedUse = qr.getExtensionByUrl(INTENDED_USE_EXT);
      assertNotNull(intendedUse);
      assertInstanceOf(CodeableConcept.class, intendedUse.getValue());
      CodeableConcept cc = (CodeableConcept) intendedUse.getValue();
      assertEquals(1, cc.getCoding().size());
      Coding coding = cc.getCodingFirstRep();
      assertEquals(CrdConstants.DOC_REASON_SYSTEM, coding.getSystem());
      assertFalse(coding.hasVersion());
      assertEquals("withorder", coding.getCode());
      assertEquals("Include with order", coding.getDisplay());
    }

    @Test
    @DisplayName("QUESTIONNAIRE: all orders get qr-context")
    void questionnaire_allOrders() {
      DeviceRequest order1 = new DeviceRequest();
      order1.setId("dr-1");
      ServiceRequest order2 = new ServiceRequest();
      order2.setId("sr-1");
      List<Resource> orders = List.of(order1, order2);

      DtrResponseBuilder.PrepopulationResult result = builder.buildResponse(testQ, testCoverage,
          questionnaireProvenance(), orders, List.of());
      QuestionnaireResponse qr = result.response();

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

      DtrQuestionnaireResolver.ResolvedQuestionnaire provenance = new DtrQuestionnaireResolver.ResolvedQuestionnaire(
          "http://example.org/Questionnaire/test|1.0", testQ,
          DtrQuestionnaireResolver.ResolutionPath.ORDER, sourceOrderIds, null);

      DtrResponseBuilder.PrepopulationResult result = builder.buildResponse(testQ, testCoverage, provenance, orders,
          List.of());
      QuestionnaireResponse qr = result.response();

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

      DtrQuestionnaireResolver.ResolvedQuestionnaire provenance = new DtrQuestionnaireResolver.ResolvedQuestionnaire(
          "http://example.org/Questionnaire/test|1.0", testQ,
          DtrQuestionnaireResolver.ResolutionPath.BOTH, sourceOrderIds, null);

      DtrResponseBuilder.PrepopulationResult result = builder.buildResponse(testQ, testCoverage, provenance, orders,
          List.of());
      QuestionnaireResponse qr = result.response();

      List<Extension> contextExts = qr.getExtensionsByUrl(QR_CONTEXT_EXT);
      assertEquals(2, contextExts.size());
    }
  }

  @Nested
  @DisplayName("CQL Pre-population")
  class PrepopulationTests {

    @Test
    @DisplayName("Pre-populated answers have information-origin extension with auto code")
    void populateSuccess_informationOrigin() {
      QuestionnaireResponse populatedQr = buildPopulatedQr();
      stubPopulateReturns(populatedQr);

      DtrResponseBuilder.PrepopulationResult result = builder.buildResponse(testQ, testCoverage,
          questionnaireProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      // Item answer should have information-origin
      QuestionnaireResponseItemComponent item1 = qr.getItem().get(0);
      assertNull(item1.getExtensionByUrl(INFO_ORIGIN_EXT),
          "information-origin should not be on item");
      QuestionnaireResponseItemAnswerComponent item1Answer = item1.getAnswer().get(0);
      Extension originExt = item1Answer.getExtensionByUrl(INFO_ORIGIN_EXT);
      assertNotNull(originExt, "Answer should have information-origin extension");
      Extension sourceExt = originExt.getExtensionByUrl("source");
      assertNotNull(sourceExt, "information-origin should have source sub-extension");
      assertTrue(sourceExt.getValue() instanceof CodeType);
      assertEquals("auto-server", ((CodeType) sourceExt.getValue()).getValue());

      // Nested item answer should also have information-origin
      QuestionnaireResponseItemComponent nested = item1.getItem().get(0);
      assertNull(nested.getExtensionByUrl(INFO_ORIGIN_EXT),
          "information-origin should not be on nested item");
      assertNotNull(nested.getAnswer().get(0).getExtensionByUrl(INFO_ORIGIN_EXT),
          "Nested answer should have information-origin");

      // Item without answer should NOT have information-origin
      QuestionnaireResponseItemComponent item2 = qr.getItem().get(1);
      assertNull(item2.getExtensionByUrl(INFO_ORIGIN_EXT),
          "Item without answer should not have information-origin");
    }

    @Test
    @DisplayName("information-origin recurses through answer.item descendants")
    void populateSuccess_informationOrigin_answerItemRecursion() {
      QuestionnaireResponse populatedQr = new QuestionnaireResponse();
      QuestionnaireResponseItemComponent parent = populatedQr.addItem().setLinkId("1");
      QuestionnaireResponseItemAnswerComponent parentAnswer = parent.addAnswer().setValue(new StringType("parent"));
      QuestionnaireResponseItemComponent nestedUnderAnswer = parentAnswer.addItem().setLinkId("1.1");
      nestedUnderAnswer.addAnswer().setValue(new StringType("nested"));
      stubPopulateReturns(populatedQr);

      DtrResponseBuilder.PrepopulationResult result = builder.buildResponse(testQ, testCoverage,
          questionnaireProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      QuestionnaireResponseItemComponent item1 = qr.getItem().get(0);
      QuestionnaireResponseItemAnswerComponent item1Answer = item1.getAnswer().get(0);
      QuestionnaireResponseItemComponent nested = item1Answer.getItem().get(0);

      assertNotNull(item1Answer.getExtensionByUrl(INFO_ORIGIN_EXT),
          "Parent answer should have information-origin");
      assertNotNull(nested.getAnswer().get(0).getExtensionByUrl(INFO_ORIGIN_EXT),
          "Answer item descendants should have information-origin");
    }

    @Test
    @DisplayName("Patient in repository: no Patient in data bundle (avoids duplicate with repository)")
    void populateSuccess_patientInRepo_notInBundle() {
      Patient realPatient = new Patient();
      realPatient.setId("pat-1");
      realPatient.addName().setFamily("Smith").addGiven("John");
      doReturn(realPatient).when(mockPatientDao).read(any(), any());

      QuestionnaireResponse populatedQr = buildPopulatedQr();
      stubPopulateReturns(populatedQr);

      builder.buildResponse(testQ, testCoverage, questionnaireProvenance(), List.of(), List.of());

      ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
      verify(mockProcessor).populate(any(IBaseResource.class), any(),
          any(), any(), bundleCaptor.capture(), any());

      Bundle dataBundle = bundleCaptor.getValue();
      boolean hasPatient = dataBundle.getEntry().stream()
          .map(Bundle.BundleEntryComponent::getResource)
          .anyMatch(Patient.class::isInstance);

      assertFalse(hasPatient,
          "Data bundle should NOT contain Patient when it exists in the repository");
    }

    @Test
    @DisplayName("Patient not in repository: stub Patient in data bundle for CQL context")
    void populateSuccess_patientNotInRepo_stubInBundle() {
      QuestionnaireResponse populatedQr = buildPopulatedQr();
      stubPopulateReturns(populatedQr);

      builder.buildResponse(testQ, testCoverage, questionnaireProvenance(), List.of(), List.of());

      ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
      verify(mockProcessor).populate(any(IBaseResource.class), any(),
          any(), any(), bundleCaptor.capture(), any());

      Bundle dataBundle = bundleCaptor.getValue();
      Patient bundlePatient = dataBundle.getEntry().stream()
          .map(Bundle.BundleEntryComponent::getResource)
          .filter(Patient.class::isInstance)
          .map(Patient.class::cast)
          .findFirst().orElse(null);

      assertNotNull(bundlePatient, "Data bundle should contain a Patient stub");
      assertEquals("pat-1", bundlePatient.getIdElement().getIdPart());
      assertFalse(bundlePatient.hasName(), "Stub Patient should not have name");
    }

    @Test
    @DisplayName("Absolute beneficiary reference keeps base URL for populate subject and avoids local collision")
    void populateSuccess_absoluteBeneficiaryReference() {
      Patient localCollision = new Patient();
      localCollision.setId("pat-abs");
      doReturn(localCollision).when(mockPatientDao).read(any(), any());

      testCoverage.setBeneficiary(new Reference("https://payer.example/fhir/Patient/pat-abs"));
      QuestionnaireResponse populatedQr = buildPopulatedQr();
      stubPopulateReturns(populatedQr);

      builder.buildResponse(testQ, testCoverage, questionnaireProvenance(), List.of(), List.of());

      ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
      ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
      verify(mockProcessor).populate(any(IBaseResource.class), subjectCaptor.capture(),
          any(), any(), bundleCaptor.capture(), any());

      assertEquals("https://payer.example/fhir/Patient/pat-abs", subjectCaptor.getValue());
      verify(mockPatientDao, never()).read(any(), any());

      Patient bundlePatient = bundleCaptor.getValue().getEntry().stream()
          .map(Bundle.BundleEntryComponent::getResource)
          .filter(Patient.class::isInstance)
          .map(Patient.class::cast)
          .findFirst().orElse(null);
      assertNotNull(bundlePatient, "Data bundle should contain Patient from absolute beneficiary reference");
      assertEquals("https://payer.example/fhir/Patient/pat-abs",
          bundlePatient.getIdElement().toVersionless().getValue());
    }

    @Test
    @DisplayName("Pre-populated QR still gets DTR extensions")
    void populateSuccess_dtrExtensionsPresent() {
      QuestionnaireResponse populatedQr = buildPopulatedQr();
      stubPopulateReturns(populatedQr);

      DtrResponseBuilder.PrepopulationResult result = builder.buildResponse(testQ, testCoverage,
          questionnaireProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      assertTrue(qr.getMeta().hasProfile(QR_PROFILE));
      assertNotNull(qr.getExtensionByUrl(QR_COVERAGE_EXT));
      assertNotNull(qr.getExtensionByUrl(INTENDED_USE_EXT));
    }

    @Test
    @DisplayName("populate() returns null: fallback to empty QR with DTR extensions")
    void populateReturnsNull_fallbackToEmpty() {
      stubPopulateReturnsNull();

      DtrResponseBuilder.PrepopulationResult result = builder.buildResponse(testQ, testCoverage,
          questionnaireProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      assertTrue(qr.getMeta().hasProfile(QR_PROFILE));
      assertNotNull(qr.getExtensionByUrl(QR_COVERAGE_EXT));
      assertTrue(qr.getItem().isEmpty());
    }

    @Test
    @DisplayName("populate() throws exception: fallback to empty QR with warning")
    void populateThrows_fallbackWithWarning() {
      stubPopulateThrows(new RuntimeException("CQL engine error"));

      DtrResponseBuilder.PrepopulationResult result = builder.buildResponse(testQ, testCoverage,
          questionnaireProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      assertTrue(qr.getMeta().hasProfile(QR_PROFILE));
      assertTrue(qr.getItem().isEmpty());
      assertFalse(result.warnings().isEmpty(), "Should have a warning about the failure");
      assertTrue(result.warnings().get(0).contains("CQL engine error"));
    }

    @Test
    @DisplayName("Warnings from pre-population are collected")
    void populateWarnings_collected() {
      QuestionnaireResponse populatedQr = buildPopulatedQr();
      OperationOutcome oo = new OperationOutcome();
      oo.addIssue()
          .setSeverity(OperationOutcome.IssueSeverity.WARNING)
          .setDiagnostics("Expression evaluation returned null for item 2.1");
      populatedQr.addContained(oo);
      stubPopulateReturns(populatedQr);

      DtrResponseBuilder.PrepopulationResult result = builder.buildResponse(testQ, testCoverage,
          questionnaireProvenance(), List.of(), List.of());

      assertFalse(result.warnings().isEmpty());
      assertTrue(result.warnings().stream()
          .anyMatch(w -> w.contains("Expression evaluation returned null")));
    }
  }

  @Nested
  @DisplayName("Adaptive Questionnaire Detection")
  class AdaptiveDetectionTests {

    @Test
    @DisplayName("Detects adaptive via questionnaireAdaptive extension (primary signal)")
    void detectsViaExtension() {
      Questionnaire q = new Questionnaire();
      q.addExtension(QUESTIONNAIRE_ADAPTIVE_EXT, new BooleanType(true));
      assertTrue(DtrResponseBuilder.isAdaptiveQuestionnaire(q));
    }

    @Test
    @DisplayName("Detects adaptive via meta.profile (fallback signal)")
    void detectsViaProfile() {
      Questionnaire q = new Questionnaire();
      q.getMeta().addProfile("http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt");
      assertTrue(DtrResponseBuilder.isAdaptiveQuestionnaire(q));
    }

    @Test
    @DisplayName("Detects adaptive when both extension and profile present")
    void detectsViaBoth() {
      Questionnaire q = new Questionnaire();
      q.addExtension(QUESTIONNAIRE_ADAPTIVE_EXT, new BooleanType(true));
      q.getMeta().addProfile("http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt");
      assertTrue(DtrResponseBuilder.isAdaptiveQuestionnaire(q));
    }

    @Test
    @DisplayName("Returns false for standard profile without extension")
    void standardProfileNotAdaptive() {
      Questionnaire q = new Questionnaire();
      q.getMeta().addProfile("http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-r4");
      assertFalse(DtrResponseBuilder.isAdaptiveQuestionnaire(q));
    }

    @Test
    @DisplayName("Returns false when no profiles and no extension")
    void noSignalsNotAdaptive() {
      Questionnaire q = new Questionnaire();
      assertFalse(DtrResponseBuilder.isAdaptiveQuestionnaire(q));
    }
  }

  @Nested
  @DisplayName("Adaptive QuestionnaireResponse Construction")
  class AdaptiveResponseTests {

    private Questionnaire adaptiveQ;

    @BeforeEach
    void setUpAdaptive() {
      adaptiveQ = new Questionnaire();
      adaptiveQ.setId("q-adapt");
      adaptiveQ.setUrl("http://example.org/Questionnaire/adaptive");
      adaptiveQ.setVersion("2.0");
      adaptiveQ.getMeta().addProfile("http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-questionnaire-adapt");
    }

    private DtrQuestionnaireResolver.ResolvedQuestionnaire adaptiveProvenance() {
      return new DtrQuestionnaireResolver.ResolvedQuestionnaire(
          "http://example.org/Questionnaire/adaptive|2.0", adaptiveQ,
          DtrQuestionnaireResolver.ResolutionPath.QUESTIONNAIRE, new ArrayList<>(), null);
    }

    @Test
    @DisplayName("Adaptive QR has dtr-questionnaireresponse-adapt profile")
    void adaptiveProfile() {
      DtrResponseBuilder.PrepopulationResult result = builder.buildAdaptiveResponse(adaptiveQ, testCoverage,
          adaptiveProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      assertTrue(qr.getMeta().hasProfile(QR_ADAPT_PROFILE));
      assertFalse(qr.getMeta().hasProfile(QR_PROFILE),
          "Adaptive QR should NOT have the standard profile");
    }

    @Test
    @DisplayName("Adaptive QR starts with no items (pre-population deferred to $next-question)")
    void adaptiveQr_noItems() {
      // Even with items on the source questionnaire, the adaptive QR should be empty
      adaptiveQ.addItem().setLinkId("1").setText("Some question");
      DtrResponseBuilder.PrepopulationResult result = builder.buildAdaptiveResponse(adaptiveQ, testCoverage,
          adaptiveProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      assertTrue(qr.getItem().isEmpty(), "Adaptive QR should start with no items");
    }

    @Test
    @DisplayName("Adaptive QR has a generated UUID ID")
    void generatedUuid() {
      DtrResponseBuilder.PrepopulationResult result = builder.buildAdaptiveResponse(adaptiveQ, testCoverage,
          adaptiveProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      assertNotNull(qr.getIdElement().getIdPart());
      // Verify it's a valid UUID format
      assertDoesNotThrow(() -> java.util.UUID.fromString(qr.getIdElement().getIdPart()));
    }

    @Test
    @DisplayName("Adaptive QR contains a contained Questionnaire with no items")
    void containedQuestionnaire() {
      DtrResponseBuilder.PrepopulationResult result = builder.buildAdaptiveResponse(adaptiveQ, testCoverage,
          adaptiveProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      assertEquals(1, qr.getContained().size());
      assertInstanceOf(Questionnaire.class, qr.getContained().get(0));
      Questionnaire contained = (Questionnaire) qr.getContained().get(0);
      assertTrue(contained.getItem().isEmpty(), "Contained questionnaire should have no items");
      assertTrue(contained.hasUrl(), "Contained questionnaire should include url");
      assertTrue(contained.hasStatus(), "Contained questionnaire should include status");
      assertTrue(contained.hasSubjectType(), "Contained questionnaire should include subjectType");
    }

    @Test
    @DisplayName("Contained Questionnaire has derivedFrom pointing to adaptive Q canonical")
    void containedDerivedFrom() {
      DtrResponseBuilder.PrepopulationResult result = builder.buildAdaptiveResponse(adaptiveQ, testCoverage,
          adaptiveProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      Questionnaire contained = (Questionnaire) qr.getContained().get(0);
      assertFalse(contained.getDerivedFrom().isEmpty());
      assertEquals("http://example.org/Questionnaire/adaptive|2.0",
          contained.getDerivedFrom().get(0).getValue());
    }

    @Test
    @DisplayName("Contained Questionnaire has questionnaireAdaptive extension as valueUrl")
    void questionnaireAdaptiveExtension() {
      DtrResponseBuilder.PrepopulationResult result = builder.buildAdaptiveResponse(adaptiveQ, testCoverage,
          adaptiveProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      Questionnaire contained = (Questionnaire) qr.getContained().get(0);
      Extension adaptiveExt = contained.getExtensionByUrl(QUESTIONNAIRE_ADAPTIVE_EXT);
      assertNotNull(adaptiveExt, "Should have questionnaireAdaptive extension");
      assertInstanceOf(UrlType.class, adaptiveExt.getValue());
      assertEquals("http://payer.example/fhir/Questionnaire/$next-question",
          ((UrlType) adaptiveExt.getValue()).asStringValue());
    }

    @Test
    @DisplayName("Adaptive QR has required DTR extensions")
    void dtrExtensions() {
      DeviceRequest order = new DeviceRequest();
      order.setId("dr-1");

      DtrResponseBuilder.PrepopulationResult result = builder.buildAdaptiveResponse(adaptiveQ, testCoverage,
          adaptiveProvenance(), List.of(order), List.of());
      QuestionnaireResponse qr = result.response();

      assertNotNull(qr.getExtensionByUrl(QR_COVERAGE_EXT), "Should have qr-coverage");
      assertNotNull(qr.getExtensionByUrl(INTENDED_USE_EXT), "Should have intendedUse");
      assertFalse(qr.getExtensionsByUrl(QR_CONTEXT_EXT).isEmpty(), "Should have qr-context");
    }

    @Test
    @DisplayName("Adaptive QR has correct status, questionnaire, and subject")
    void basicFields() {
      DtrResponseBuilder.PrepopulationResult result = builder.buildAdaptiveResponse(adaptiveQ, testCoverage,
          adaptiveProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      assertEquals(QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS, qr.getStatus());
      assertEquals("#contained-questionnaire", qr.getQuestionnaire());
      assertEquals("Patient/pat-1", qr.getSubject().getReference());
      assertNotNull(qr.getAuthored());
    }

    @Test
    @DisplayName("Adaptive extension uses server_address fallback when explicit URL is blank")
    void adaptiveExtensionWithBlankExplicitUrl() {
      AppProperties fallbackProps = mock(AppProperties.class);
      when(fallbackProps.getServer_address()).thenReturn("http://localhost:8080/fhir");
      DtrResponseBuilder fallbackBuilder = new DtrResponseBuilder(mockFactory, mockDaoRegistry,
          new DtrAdaptiveProperties(""), fallbackProps);

      DtrResponseBuilder.PrepopulationResult result = fallbackBuilder.buildAdaptiveResponse(adaptiveQ, testCoverage,
          adaptiveProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      Questionnaire contained = (Questionnaire) qr.getContained().get(0);
      Extension adaptiveExt = contained.getExtensionByUrl(QUESTIONNAIRE_ADAPTIVE_EXT);
      assertNotNull(adaptiveExt, "Should keep questionnaireAdaptive extension");
      assertEquals("http://localhost:8080/fhir/Questionnaire/$next-question",
          ((UrlType) adaptiveExt.getValue()).asStringValue());
      assertTrue(result.warnings().isEmpty(), "No warnings when fallback succeeds");
    }

    @Test
    @DisplayName("Adaptive extension uses trimmed server_address with trailing slash")
    void adaptiveExtensionWithTrailingSlashServerAddress() {
      AppProperties slashProps = mock(AppProperties.class);
      when(slashProps.getServer_address()).thenReturn("http://localhost:8080/fhir/");
      DtrResponseBuilder slashBuilder = new DtrResponseBuilder(mockFactory, mockDaoRegistry,
          new DtrAdaptiveProperties(""), slashProps);

      DtrResponseBuilder.PrepopulationResult result = slashBuilder.buildAdaptiveResponse(adaptiveQ, testCoverage,
          adaptiveProvenance(), List.of(), List.of());

      Questionnaire contained = (Questionnaire) result.response().getContained().get(0);
      Extension adaptiveExt = contained.getExtensionByUrl(QUESTIONNAIRE_ADAPTIVE_EXT);
      assertEquals("http://localhost:8080/fhir/Questionnaire/$next-question",
          ((UrlType) adaptiveExt.getValue()).asStringValue());
    }

    @Test
    @DisplayName("Adaptive extension uses explicit configured URL")
    void adaptiveExtensionWithExplicitUrl() {
      DtrResponseBuilder.PrepopulationResult result = builder.buildAdaptiveResponse(adaptiveQ, testCoverage,
          adaptiveProvenance(), List.of(), List.of());

      Questionnaire contained = (Questionnaire) result.response().getContained().get(0);
      Extension adaptiveExt = contained.getExtensionByUrl(QUESTIONNAIRE_ADAPTIVE_EXT);
      assertEquals("http://payer.example/fhir/Questionnaire/$next-question",
          ((UrlType) adaptiveExt.getValue()).asStringValue());
    }

    @Test
    @DisplayName("Adaptive extension defaults to localhost URL when URL sources are unavailable")
    void missingBothUrlsStillAddsAdaptiveExtension() {
      AppProperties emptyProps = mock(AppProperties.class);
      when(emptyProps.getServer_address()).thenReturn("");
      DtrResponseBuilder noUrlBuilder = new DtrResponseBuilder(mockFactory, mockDaoRegistry,
          new DtrAdaptiveProperties(""), emptyProps);

      DtrResponseBuilder.PrepopulationResult result = noUrlBuilder.buildAdaptiveResponse(adaptiveQ, testCoverage,
          adaptiveProvenance(), List.of(), List.of());
      QuestionnaireResponse qr = result.response();

      Questionnaire contained = (Questionnaire) qr.getContained().get(0);
      assertNotNull(contained.getExtensionByUrl(QUESTIONNAIRE_ADAPTIVE_EXT),
          "Extension should still be present");
      assertEquals("http://localhost:8080/fhir/Questionnaire/$next-question",
          ((UrlType) contained.getExtensionByUrl(QUESTIONNAIRE_ADAPTIVE_EXT).getValue()).asStringValue());
      assertTrue(result.warnings().isEmpty());
    }
  }
}
