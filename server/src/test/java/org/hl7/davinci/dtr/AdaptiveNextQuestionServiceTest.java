package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemOperator;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseStatus;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opencds.cqf.fhir.cr.hapi.common.IQuestionnaireProcessorFactory;
import org.opencds.cqf.fhir.cr.questionnaire.QuestionnaireProcessor;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;

@ExtendWith(MockitoExtension.class)
class AdaptiveNextQuestionServiceTest {

  @Mock private DaoRegistry daoRegistry;
  @Mock private DtrSubQuestionnaireAssembler subQAssembler;
  @Mock private IFhirResourceDao<Questionnaire> questionnaireDao;
  @Mock private IBundleProvider bundleProvider;
  @Mock private IQuestionnaireProcessorFactory questionnaireProcessorFactory;
  @Mock private QuestionnaireProcessor questionnaireProcessor;

  private EnableWhenEvaluator enableWhenEvaluator;
  private AdaptiveNextQuestionService service;

  private static final String QR_ID = "test-qr-id";
  private static final String Q_CANONICAL = "http://example.org/Questionnaire/Test|1.0.0";
  private static final String NEXT_Q_OUTPUT_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-next-question-output-parameters";

  @BeforeEach
  void setUp() {
    enableWhenEvaluator = new EnableWhenEvaluator();
    service = new AdaptiveNextQuestionService(
        daoRegistry, subQAssembler, enableWhenEvaluator, questionnaireProcessorFactory);
  }

  // ============================================================
  // HAPPY PATH: BATCH DELIVERY
  // ============================================================

  @Nested
  @DisplayName("Batch delivery")
  class BatchDelivery {

    @Test
    @DisplayName("First call delivers all groups up to enableWhen boundary")
    void firstCall_deliversGroupsUpToEnableWhenBoundary() {
      // Source questionnaire: groups 1-5 (no enableWhen), group 6 (enableWhen on "4.1")
      Questionnaire sourceQ = buildOpioidLikeQuestionnaire();
      QuestionnaireResponse qr = buildQrWithEmptyContainedQ();

      stubDao(sourceQ);

      Parameters result = service.processNextQuestion(qr);

      assertNotNull(result);
      assertTrue(result.getMeta().hasProfile(NEXT_Q_OUTPUT_PROFILE));

      QuestionnaireResponse outputQr = (QuestionnaireResponse) result.getParameter()
          .get(0).getResource();
      Questionnaire containedQ = extractContainedQ(outputQr);

      // Should deliver groups 1-5, stop at group 6 (enableWhen can't be evaluated)
      assertEquals(5, containedQ.getItem().size());
      assertEquals("1", containedQ.getItem().get(0).getLinkId());
      assertEquals("5", containedQ.getItem().get(4).getLinkId());
    }

    @Test
    @DisplayName("Second call with answers evaluates enableWhen and delivers group 6")
    void secondCall_evaluatesEnableWhenAndDeliversGroup6() {
      Questionnaire sourceQ = buildOpioidLikeQuestionnaire();

      // QR with groups 1-5 already delivered and answer to 4.1
      QuestionnaireResponse qr = buildQrWithDeliveredGroupsAndAnswer();

      stubDao(sourceQ);

      Parameters result = service.processNextQuestion(qr);

      QuestionnaireResponse outputQr = (QuestionnaireResponse) result.getParameter()
          .get(0).getResource();
      Questionnaire containedQ = extractContainedQ(outputQr);

      // 5 already delivered + group 6 newly delivered = 6
      assertEquals(6, containedQ.getItem().size());
      assertEquals("6", containedQ.getItem().get(5).getLinkId());
    }

    @Test
    @DisplayName("All groups delivered > status COMPLETED")
    void allGroupsDelivered_statusCompleted() {
      Questionnaire sourceQ = buildOpioidLikeQuestionnaire();

      // QR with all 6 groups already delivered and all answers
      QuestionnaireResponse qr = buildQrWithAllGroupsDelivered();

      stubDao(sourceQ);

      Parameters result = service.processNextQuestion(qr);

      QuestionnaireResponse outputQr = (QuestionnaireResponse) result.getParameter()
          .get(0).getResource();
      assertEquals(QuestionnaireResponseStatus.COMPLETED, outputQr.getStatus());
    }

    @Test
    @DisplayName("enableWhen false > group skipped, questionnaire completed")
    void enableWhenFalse_groupSkipped() {
      // Source Q: group 1 (no enableWhen), group 2 (enableWhen q1 = true)
      Questionnaire sourceQ = new Questionnaire();
      sourceQ.setUrl("http://example.org/Questionnaire/Test");
      sourceQ.setVersion("1.0.0");
      sourceQ.addItem().setLinkId("1").setText("Group 1").setType(Questionnaire.QuestionnaireItemType.GROUP);
      QuestionnaireItemComponent group2 = sourceQ.addItem();
      group2.setLinkId("2").setText("Group 2").setType(Questionnaire.QuestionnaireItemType.GROUP);
      group2.addEnableWhen()
          .setQuestion("1.1")
          .setOperator(QuestionnaireItemOperator.EQUAL)
          .setAnswer(new BooleanType(true));

      // QR with group 1 delivered and answer 1.1=false
      QuestionnaireResponse qr = buildQr(QR_ID);
      Questionnaire contained = new Questionnaire();
      contained.setId("contained-q");
      contained.setDerivedFrom(List.of(new CanonicalType(Q_CANONICAL)));
      contained.addItem().setLinkId("1").setText("Group 1");
      qr.addContained(contained);
      qr.setQuestionnaire("#contained-q");
      qr.addItem().setLinkId("1.1").addAnswer().setValue(new BooleanType(false));

      stubDao(sourceQ);

      Parameters result = service.processNextQuestion(qr);

      QuestionnaireResponse outputQr = (QuestionnaireResponse) result.getParameter()
          .get(0).getResource();
      assertEquals(QuestionnaireResponseStatus.COMPLETED, outputQr.getStatus());
      // Contained Q should still only have group 1 (group 2 was skipped)
      Questionnaire resultContained = extractContainedQ(outputQr);
      assertEquals(1, resultContained.getItem().size());
    }

    @Test
    @DisplayName("Unanswered dependency is treated as not answered and does not complete")
    void unansweredDependency_doesNotComplete() {
      // Source Q: group 1 already delivered, group 2 enabled when question 1.1 exists
      Questionnaire sourceQ = new Questionnaire();
      sourceQ.setUrl("http://example.org/Questionnaire/Test");
      sourceQ.setVersion("1.0.0");
      sourceQ.addItem().setLinkId("1").setText("Group 1").setType(Questionnaire.QuestionnaireItemType.GROUP);
      QuestionnaireItemComponent group2 = sourceQ.addItem();
      group2.setLinkId("2").setText("Group 2").setType(Questionnaire.QuestionnaireItemType.GROUP);
      group2.addEnableWhen()
          .setQuestion("1.1")
          .setOperator(QuestionnaireItemOperator.EXISTS)
          .setAnswer(new BooleanType(true));

      QuestionnaireResponse qr = buildQr(QR_ID);
      Questionnaire contained = new Questionnaire();
      contained.setId("contained-q");
      contained.setDerivedFrom(List.of(new CanonicalType(Q_CANONICAL)));
      contained.addItem().setLinkId("1").setText("Group 1");
      qr.addContained(contained);
      qr.setQuestionnaire("#contained-q");
      qr.addItem().setLinkId("1.1"); // displayed but unanswered

      stubDao(sourceQ);

      Parameters result = service.processNextQuestion(qr);

      QuestionnaireResponse outputQr = (QuestionnaireResponse) result.getParameter()
          .get(0).getResource();
      Questionnaire resultContained = extractContainedQ(outputQr);

      assertEquals(QuestionnaireResponseStatus.INPROGRESS, outputQr.getStatus());
      assertEquals(1, resultContained.getItem().size());
    }
  }

  // ============================================================
  // PRE-POPULATION
  // ============================================================

  @Nested
  @DisplayName("Adaptive pre-population")
  class AdaptivePrepopulation {

    @Test
    @DisplayName("First call adds pre-populated answers for delivered items")
    void firstCall_populatesDeliveredItems() {
      Questionnaire sourceQ = new Questionnaire();
      sourceQ.setUrl("http://example.org/Questionnaire/Test");
      sourceQ.setVersion("1.0.0");
      QuestionnaireItemComponent group = sourceQ.addItem()
          .setLinkId("1")
          .setText("Patient Information")
          .setType(Questionnaire.QuestionnaireItemType.GROUP);
      group.addItem()
          .setLinkId("1.PBI.2")
          .setText("First Name")
          .setType(Questionnaire.QuestionnaireItemType.STRING);

      QuestionnaireResponse qr = buildQrWithEmptyContainedQ();
      qr.setSubject(new Reference("Patient/example"));

      QuestionnaireResponse populated = new QuestionnaireResponse();
      populated.addItem()
          .setLinkId("1.PBI.2")
          .addAnswer()
          .setValue(new StringType("Jane"));

      stubDao(sourceQ);
      when(questionnaireProcessorFactory.create(any())).thenReturn(questionnaireProcessor);
      when(questionnaireProcessor.populate(
          any(IBaseResource.class), any(), any(), any(), any(), any()))
          .thenReturn(populated);

      Parameters result = service.processNextQuestion(qr);
      QuestionnaireResponse outputQr = (QuestionnaireResponse) result.getParameter()
          .get(0).getResource();

      QuestionnaireResponseItemComponent firstName =
          findItemByLinkId(outputQr.getItem(), "1.PBI.2");
      assertNotNull(firstName, "Pre-populated first name should be present");
      assertTrue(firstName.hasAnswer());
      assertEquals("Jane", ((StringType) firstName.getAnswerFirstRep().getValue()).getValue());
    }

    @Test
    @DisplayName("Pre-population does not override existing answers")
    void prepopulation_doesNotOverrideExistingAnswers() {
      Questionnaire sourceQ = new Questionnaire();
      sourceQ.setUrl("http://example.org/Questionnaire/Test");
      sourceQ.setVersion("1.0.0");
      QuestionnaireItemComponent group = sourceQ.addItem()
          .setLinkId("1")
          .setText("Patient Information")
          .setType(Questionnaire.QuestionnaireItemType.GROUP);
      group.addItem()
          .setLinkId("1.PBI.2")
          .setText("First Name")
          .setType(Questionnaire.QuestionnaireItemType.STRING);

      QuestionnaireResponse qr = buildQrWithEmptyContainedQ();
      qr.setSubject(new Reference("Patient/example"));
      qr.addItem()
          .setLinkId("1.PBI.2")
          .addAnswer()
          .setValue(new StringType("Custom"));

      QuestionnaireResponse populated = new QuestionnaireResponse();
      populated.addItem()
          .setLinkId("1.PBI.2")
          .addAnswer()
          .setValue(new StringType("Jane"));

      stubDao(sourceQ);
      when(questionnaireProcessorFactory.create(any())).thenReturn(questionnaireProcessor);
      when(questionnaireProcessor.populate(
          any(IBaseResource.class), any(), any(), any(), any(), any()))
          .thenReturn(populated);

      Parameters result = service.processNextQuestion(qr);
      QuestionnaireResponse outputQr = (QuestionnaireResponse) result.getParameter()
          .get(0).getResource();

      QuestionnaireResponseItemComponent firstName =
          findItemByLinkId(outputQr.getItem(), "1.PBI.2");
      assertNotNull(firstName);
      assertEquals("Custom", ((StringType) firstName.getAnswerFirstRep().getValue()).getValue());
    }

    @Test
    @DisplayName("Pre-population includes transient qr-context resources from contained QR")
    void prepopulation_includesTransientContainedContextResources() {
      Questionnaire sourceQ = new Questionnaire();
      sourceQ.setUrl("http://example.org/Questionnaire/Test");
      sourceQ.setVersion("1.0.0");
      sourceQ.addItem()
          .setLinkId("1")
          .setText("Group 1")
          .setType(Questionnaire.QuestionnaireItemType.GROUP);

      QuestionnaireResponse qr = buildQrWithEmptyContainedQ();
      qr.setSubject(new Reference("Patient/example"));

      MedicationRequest transientOrder = new MedicationRequest();
      transientOrder.setId("med-rx-1");
      qr.addContained(transientOrder);

      Extension contextExt = new Extension(
          "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-context");
      contextExt.setValue(new Reference("MedicationRequest/med-rx-1"));
      qr.addExtension(contextExt);

      stubDao(sourceQ);
      when(questionnaireProcessorFactory.create(any())).thenReturn(questionnaireProcessor);
      when(questionnaireProcessor.populate(
          any(IBaseResource.class), any(), any(), any(), any(), any()))
          .thenReturn(new QuestionnaireResponse());

      service.processNextQuestion(qr);

      var bundleCaptor = org.mockito.ArgumentCaptor.forClass(Bundle.class);
      verify(questionnaireProcessor).populate(
          any(IBaseResource.class), any(), any(), any(), bundleCaptor.capture(), any());

      boolean hasTransientOrder = bundleCaptor.getValue().getEntry().stream()
          .map(Bundle.BundleEntryComponent::getResource)
          .anyMatch(resource -> resource instanceof MedicationRequest
              && "med-rx-1".equals(resource.getIdElement().getIdPart()));

      assertTrue(hasTransientOrder,
          "Data bundle should include transient qr-context resource from contained QR");
    }

      @Test
      @DisplayName("Pre-population resolves external qr-context resources through DAO")
      @SuppressWarnings({ "rawtypes" })
      void prepopulation_resolvesExternalQrContextFromDao() {
        Questionnaire sourceQ = buildSimpleQuestionnaire();
        QuestionnaireResponse qr = buildQrWithEmptyContainedQ();
        qr.setSubject(new Reference("Patient/example"));

        Extension contextExt = new Extension(
          "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-context");
        contextExt.setValue(new Reference("MedicationRequest/med-rx-2"));
        qr.addExtension(contextExt);

        IFhirResourceDao medicationRequestDao = mock(IFhirResourceDao.class);
        MedicationRequest persistedOrder = new MedicationRequest();
        persistedOrder.setId("MedicationRequest/med-rx-2");

        when(daoRegistry.getResourceDao("MedicationRequest")).thenReturn(medicationRequestDao);
        when(medicationRequestDao.read(any(), any())).thenReturn(persistedOrder);

        stubDao(sourceQ);
        when(questionnaireProcessorFactory.create(any())).thenReturn(questionnaireProcessor);
        when(questionnaireProcessor.populate(
          any(IBaseResource.class), any(), any(), any(), any(), any()))
          .thenReturn(new QuestionnaireResponse());

        service.processNextQuestion(qr);

        var bundleCaptor = org.mockito.ArgumentCaptor.forClass(Bundle.class);
        verify(questionnaireProcessor).populate(
          any(IBaseResource.class), any(), any(), any(), bundleCaptor.capture(), any());

        boolean hasResolvedOrder = bundleCaptor.getValue().getEntry().stream()
          .map(Bundle.BundleEntryComponent::getResource)
          .anyMatch(resource -> resource instanceof MedicationRequest
            && "med-rx-2".equals(resource.getIdElement().getIdPart()));

        assertTrue(hasResolvedOrder,
          "Data bundle should include DAO-resolved qr-context resource");
      }

      @Test
      @DisplayName("Pre-population resolves external qr-coverage resources through DAO")
      @SuppressWarnings({ "rawtypes" })
      void prepopulation_resolvesExternalCoverageFromDao() {
        Questionnaire sourceQ = buildSimpleQuestionnaire();
        QuestionnaireResponse qr = buildQrWithEmptyContainedQ();
        qr.setSubject(new Reference("Patient/example"));

        Extension coverageExt = new Extension(
          "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/qr-coverage");
        coverageExt.setValue(new Reference("Coverage/cov-2"));
        qr.addExtension(coverageExt);

        IFhirResourceDao coverageDao = mock(IFhirResourceDao.class);
        Coverage persistedCoverage = new Coverage();
        persistedCoverage.setId("Coverage/cov-2");

        when(daoRegistry.getResourceDao("Coverage")).thenReturn(coverageDao);
        when(coverageDao.read(any(), any())).thenReturn(persistedCoverage);

        stubDao(sourceQ);
        when(questionnaireProcessorFactory.create(any())).thenReturn(questionnaireProcessor);
        when(questionnaireProcessor.populate(
          any(IBaseResource.class), any(), any(), any(), any(), any()))
          .thenReturn(new QuestionnaireResponse());

        service.processNextQuestion(qr);

        var bundleCaptor = org.mockito.ArgumentCaptor.forClass(Bundle.class);
        verify(questionnaireProcessor).populate(
          any(IBaseResource.class), any(), any(), any(), bundleCaptor.capture(), any());

        boolean hasResolvedCoverage = bundleCaptor.getValue().getEntry().stream()
          .map(Bundle.BundleEntryComponent::getResource)
          .anyMatch(resource -> resource instanceof Coverage
            && "cov-2".equals(resource.getIdElement().getIdPart()));

        assertTrue(hasResolvedCoverage,
          "Data bundle should include DAO-resolved qr-coverage resource");
      }

    @Test
    @DisplayName("Pre-population preserves nested item structure when merging answers")
    void prepopulation_preservesNestedItemStructure() {
      Questionnaire sourceQ = new Questionnaire();
      sourceQ.setUrl("http://example.org/Questionnaire/Test");
      sourceQ.setVersion("1.0.0");
      QuestionnaireItemComponent group = sourceQ.addItem()
          .setLinkId("1")
          .setText("Patient Information")
          .setType(Questionnaire.QuestionnaireItemType.GROUP);
      group.addItem()
          .setLinkId("1.PBI.2")
          .setText("First Name")
          .setType(Questionnaire.QuestionnaireItemType.STRING);

      QuestionnaireResponse qr = buildQrWithEmptyContainedQ();
      qr.setSubject(new Reference("Patient/example"));

      QuestionnaireResponse populated = new QuestionnaireResponse();
      QuestionnaireResponseItemComponent populatedGroup = populated.addItem()
          .setLinkId("1")
          .setText("Patient Information");
      populatedGroup.addItem()
          .setLinkId("1.PBI.2")
          .addAnswer()
          .setValue(new StringType("Jane"));

      stubDao(sourceQ);
      when(questionnaireProcessorFactory.create(any())).thenReturn(questionnaireProcessor);
      when(questionnaireProcessor.populate(
          any(IBaseResource.class), any(), any(), any(), any(), any()))
          .thenReturn(populated);

      Parameters result = service.processNextQuestion(qr);
      QuestionnaireResponse outputQr = (QuestionnaireResponse) result.getParameter()
          .get(0).getResource();

      QuestionnaireResponseItemComponent rootGroup = outputQr.getItem().stream()
          .filter(item -> "1".equals(item.getLinkId()))
          .findFirst()
          .orElse(null);
      assertNotNull(rootGroup, "Parent group item should be present in the response");

      QuestionnaireResponseItemComponent nestedFirstName = rootGroup.getItem().stream()
          .filter(item -> "1.PBI.2".equals(item.getLinkId()))
          .findFirst()
          .orElse(null);
      assertNotNull(nestedFirstName, "Nested pre-populated item should remain under its parent");
      assertEquals("Jane", ((StringType) nestedFirstName.getAnswerFirstRep().getValue()).getValue());

      boolean firstNameAtRoot = outputQr.getItem().stream()
          .anyMatch(item -> "1.PBI.2".equals(item.getLinkId()));
      assertFalse(firstNameAtRoot,
          "Nested pre-populated item should not be appended at the QuestionnaireResponse root");
    }

    @Test
    @DisplayName("Pre-population includes subject patient for absolute subject references")
    void prepopulation_includesSubjectPatientForAbsoluteSubject() {
      Questionnaire sourceQ = buildSimpleQuestionnaire();
      QuestionnaireResponse qr = buildQrWithEmptyContainedQ();
      String absoluteSubject = "http://ehr.example/fhir/Patient/ext-123";
      qr.setSubject(new Reference(absoluteSubject));

      stubDao(sourceQ);
      when(questionnaireProcessorFactory.create(any())).thenReturn(questionnaireProcessor);
      when(questionnaireProcessor.populate(
          any(IBaseResource.class), any(), any(), any(), any(), any()))
          .thenReturn(new QuestionnaireResponse());

      service.processNextQuestion(qr);

      var bundleCaptor = org.mockito.ArgumentCaptor.forClass(Bundle.class);
      verify(questionnaireProcessor).populate(
          any(IBaseResource.class), any(), any(), any(), bundleCaptor.capture(), any());

      boolean hasSubjectPatient = bundleCaptor.getValue().getEntry().stream()
          .map(Bundle.BundleEntryComponent::getResource)
          .anyMatch(resource -> resource instanceof Patient
              && absoluteSubject.equals(resource.getIdElement().toVersionless().getValue()));

      assertTrue(hasSubjectPatient,
          "Data bundle should include a subject Patient stub for absolute subject references");
    }
  }

  // ============================================================
  // ERROR CASES
  // ============================================================

  @Nested
  @DisplayName("Error cases")
  class ErrorCases {

    @Test
    @DisplayName("Null QR > 400")
    void nullQr() {
      assertThrows(InvalidRequestException.class, () -> service.processNextQuestion(null));
    }

    @Test
    @DisplayName("Missing contained Questionnaire > 400")
    void missingContainedQ() {
      QuestionnaireResponse qr = buildQr(QR_ID);
      // No contained Questionnaire
      assertThrows(InvalidRequestException.class, () -> service.processNextQuestion(qr));
    }

    @Test
    @DisplayName("Missing derivedFrom in contained Questionnaire > 400")
    void missingDerivedFrom() {
      QuestionnaireResponse qr = buildQr(QR_ID);
      Questionnaire contained = new Questionnaire();
      contained.setId("contained-q");
      // No derivedFrom set
      qr.addContained(contained);
      qr.setQuestionnaire("#contained-q");

      assertThrows(InvalidRequestException.class, () -> service.processNextQuestion(qr));
    }

    @Test
    @DisplayName("Source questionnaire not found > 500")
    void sourceQuestionnaireNotFound() {
      QuestionnaireResponse qr = buildQrWithEmptyContainedQ();

      when(daoRegistry.getResourceDao(Questionnaire.class)).thenReturn(questionnaireDao);
      when(questionnaireDao.search(any(), any())).thenReturn(bundleProvider);
      when(bundleProvider.isEmpty()).thenReturn(true);

      assertThrows(InternalErrorException.class, () -> service.processNextQuestion(qr));
    }
  }

  // ============================================================
  // OUTPUT STRUCTURE
  // ============================================================

  @Nested
  @DisplayName("Output structure")
  class OutputStructure {

    @Test
    @DisplayName("Output has correct profile")
    void outputHasCorrectProfile() {
      Questionnaire sourceQ = buildSimpleQuestionnaire();
      QuestionnaireResponse qr = buildQrWithEmptyContainedQ();

      stubDao(sourceQ);

      Parameters result = service.processNextQuestion(qr);

      assertTrue(result.getMeta().hasProfile(NEXT_Q_OUTPUT_PROFILE));
    }

    @Test
    @DisplayName("Output parameter name is 'questionnaire-response'")
    void outputParameterName() {
      Questionnaire sourceQ = buildSimpleQuestionnaire();
      QuestionnaireResponse qr = buildQrWithEmptyContainedQ();

      stubDao(sourceQ);

      Parameters result = service.processNextQuestion(qr);

      assertEquals("questionnaire-response", result.getParameter().get(0).getName());
    }
  }

  // ============================================================
  // HELPERS
  // ============================================================

  private void stubDao(Questionnaire sourceQ) {
    when(daoRegistry.getResourceDao(Questionnaire.class)).thenReturn(questionnaireDao);
    when(questionnaireDao.search(any(), any())).thenReturn(bundleProvider);
    when(bundleProvider.isEmpty()).thenReturn(false);
    when(bundleProvider.getResources(0, 1)).thenReturn(List.of(sourceQ));
    when(subQAssembler.assemble(any())).thenReturn(List.of());
  }

  private QuestionnaireResponse buildQr(String id) {
    QuestionnaireResponse qr = new QuestionnaireResponse();
    qr.setId(id);
    qr.setStatus(QuestionnaireResponseStatus.INPROGRESS);
    return qr;
  }

  private QuestionnaireResponse buildQrWithEmptyContainedQ() {
    QuestionnaireResponse qr = buildQr(QR_ID);
    Questionnaire contained = new Questionnaire();
    contained.setId("contained-q");
    contained.setDerivedFrom(List.of(new CanonicalType(Q_CANONICAL)));
    qr.addContained(contained);
    qr.setQuestionnaire("#contained-q");
    return qr;
  }

  private QuestionnaireResponse buildQrWithDeliveredGroupsAndAnswer() {
    QuestionnaireResponse qr = buildQr(QR_ID);
    Questionnaire contained = new Questionnaire();
    contained.setId("contained-q");
    contained.setDerivedFrom(List.of(new CanonicalType(Q_CANONICAL)));
    // Groups 1-5 already delivered
    for (int i = 1; i <= 5; i++) {
      contained.addItem().setLinkId(String.valueOf(i)).setText("Group " + i);
    }
    qr.addContained(contained);
    qr.setQuestionnaire("#contained-q");

    // Answer to enableWhen question 4.1
    qr.addItem().setLinkId("4.1").addAnswer().setValue(new BooleanType(true));
    return qr;
  }

  private QuestionnaireResponse buildQrWithAllGroupsDelivered() {
    QuestionnaireResponse qr = buildQr(QR_ID);
    Questionnaire contained = new Questionnaire();
    contained.setId("contained-q");
    contained.setDerivedFrom(List.of(new CanonicalType(Q_CANONICAL)));
    for (int i = 1; i <= 6; i++) {
      contained.addItem().setLinkId(String.valueOf(i)).setText("Group " + i);
    }
    qr.addContained(contained);
    qr.setQuestionnaire("#contained-q");

    // All answers provided
    qr.addItem().setLinkId("4.1").addAnswer().setValue(new BooleanType(true));
    return qr;
  }

  private Questionnaire buildOpioidLikeQuestionnaire() {
    Questionnaire q = new Questionnaire();
    q.setUrl("http://example.org/Questionnaire/Test");
    q.setVersion("1.0.0");

    // Groups 1-5: no enableWhen
    for (int i = 1; i <= 5; i++) {
      QuestionnaireItemComponent group = q.addItem();
      group.setLinkId(String.valueOf(i));
      group.setText("Group " + i);
      group.setType(Questionnaire.QuestionnaireItemType.GROUP);
    }

    // Group 6: enableWhen on question 4.1 exists=true
    QuestionnaireItemComponent group6 = q.addItem();
    group6.setLinkId("6");
    group6.setText("Group 6");
    group6.setType(Questionnaire.QuestionnaireItemType.GROUP);
    group6.addEnableWhen()
        .setQuestion("4.1")
        .setOperator(QuestionnaireItemOperator.EXISTS)
        .setAnswer(new BooleanType(true));

    return q;
  }

  private Questionnaire buildSimpleQuestionnaire() {
    Questionnaire q = new Questionnaire();
    q.setUrl("http://example.org/Questionnaire/Test");
    q.setVersion("1.0.0");
    q.addItem().setLinkId("1").setText("Group 1").setType(Questionnaire.QuestionnaireItemType.GROUP);
    return q;
  }

  private Questionnaire extractContainedQ(QuestionnaireResponse qr) {
    return qr.getContained().stream()
        .filter(Questionnaire.class::isInstance)
        .map(Questionnaire.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private QuestionnaireResponseItemComponent findItemByLinkId(
      List<QuestionnaireResponseItemComponent> items, String linkId) {
    if (items == null) return null;
    for (QuestionnaireResponseItemComponent item : items) {
      if (linkId.equals(item.getLinkId())) return item;
      QuestionnaireResponseItemComponent found = findItemByLinkId(item.getItem(), linkId);
      if (found != null) return found;
      for (var answer : item.getAnswer()) {
        QuestionnaireResponseItemComponent inAnswer = findItemByLinkId(answer.getItem(), linkId);
        if (inAnswer != null) return inAnswer;
      }
    }
    return null;
  }
}
