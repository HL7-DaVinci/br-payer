package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.hl7.davinci.common.CrdConstants;
import org.hl7.davinci.common.PlanDefinitionService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.Medication;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.SupplyRequest;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.TriggerDefinition;
import org.hl7.fhir.r4.model.RequestGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

@ExtendWith(MockitoExtension.class)
class DtrQuestionnaireResolverTest {

  @Mock
  private DaoRegistry daoRegistry;

  @Mock
  private PlanDefinitionService planDefinitionService;

  @Mock
  private DtrContextRegistry contextRegistry;

  @Mock
  @SuppressWarnings("rawtypes")
  private IFhirResourceDao patientDao;

  @Mock
  @SuppressWarnings("rawtypes")
  private IFhirResourceDao questionnaireDao;

  @Mock
  @SuppressWarnings("rawtypes")
  private IFhirResourceDao medicationDao;

  @Mock
  @SuppressWarnings("rawtypes")
  private IFhirResourceDao procedureDao;

  @Mock
  @SuppressWarnings("rawtypes")
  private IFhirResourceDao libraryDao;

  private DtrQuestionnaireResolver resolver;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    resolver = new DtrQuestionnaireResolver(daoRegistry, planDefinitionService, contextRegistry);

    lenient().when(contextRegistry.lookup(any())).thenReturn(java.util.Optional.empty());
    lenient().when(daoRegistry.getResourceDao(Patient.class)).thenReturn(patientDao);
    lenient().when(daoRegistry.getResourceDao(Questionnaire.class)).thenReturn(questionnaireDao);
    lenient().when(daoRegistry.getResourceDao("Library")).thenReturn(libraryDao);
    lenient().when(patientDao.read(any(IdType.class), any(SystemRequestDetails.class)))
        .thenReturn(new Patient().setId("Patient/pat-1"));
    // Dispatch-stage filtering lives in PlanDefinitionService; run the real
    // implementation so resolver tests exercise the combined behavior
    lenient().when(planDefinitionService.removeDispatchPlansLackingEvidence(any(), any()))
        .thenCallRealMethod();
  }

  @Test
  @DisplayName("resolveContext returns the questionnaire named by the context id")
  void resolveContext_known_returnsQuestionnaire() {
    Questionnaire q = questionnaire("home-o2-std-questionnaire",
        "http://example.org/fhir/Questionnaire/home-o2-std-questionnaire", "2.2.0");
    when(questionnaireDao.read(any(IdType.class), any(SystemRequestDetails.class))).thenReturn(q);

    Questionnaire resolved = resolver.resolveContext("home-o2-std-questionnaire");

    assertEquals("http://example.org/fhir/Questionnaire/home-o2-std-questionnaire", resolved.getUrl());
  }

  @Test
  @DisplayName("resolveContext throws 422 for an unknown context id")
  void resolveContext_unknown_throws422() {
    when(questionnaireDao.read(any(IdType.class), any(SystemRequestDetails.class)))
        .thenThrow(new ResourceNotFoundException("not found"));

    assertThrows(UnprocessableEntityException.class, () -> resolver.resolveContext("missing"));
  }

  @Test
  @DisplayName("resolveContext resolves a CRD coverage-assertion id via the context registry")
  void crdAssertionContextResolvesViaRegistry() {
    when(questionnaireDao.read(any(IdType.class), any(SystemRequestDetails.class)))
        .thenThrow(new ResourceNotFoundException("not found"));

    String canonical = "http://example.org/fhir/Questionnaire/home-o2-std-questionnaire";
    DeviceRequest registeredOrder = new DeviceRequest();
    registeredOrder.setId("DeviceRequest/dr-1");
    when(contextRegistry.lookup("CRD-abc-123")).thenReturn(java.util.Optional.of(
        new DtrContextRegistry.DtrContext(List.of(canonical), registeredOrder, new Coverage())));

    Questionnaire q = questionnaire("home-o2", canonical, "2.2.0");
    when(questionnaireDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of(q));

    Questionnaire resolved = resolver.resolveContext("CRD-abc-123");

    assertEquals(canonical, resolved.getUrl());
  }

  @Test
  @DisplayName("resolveContext returns an oper-8 not-found OperationOutcome when nothing resolves")
  void unresolvableContextReturnsOper8OperationOutcome() {
    when(questionnaireDao.read(any(IdType.class), any(SystemRequestDetails.class)))
        .thenThrow(new ResourceNotFoundException("not found"));

    UnprocessableEntityException ex = assertThrows(UnprocessableEntityException.class,
        () -> resolver.resolveContext("unknown-ctx"));

    org.hl7.fhir.r4.model.OperationOutcome oo =
        (org.hl7.fhir.r4.model.OperationOutcome) ex.getOperationOutcome();
    assertEquals(org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity.ERROR,
        oo.getIssueFirstRep().getSeverity());
    assertEquals(org.hl7.fhir.r4.model.OperationOutcome.IssueType.NOTFOUND,
        oo.getIssueFirstRep().getCode());
    assertTrue(oo.getIssueFirstRep().getDiagnostics().contains("No questionnaires are associated"));
  }

  @Test
  @DisplayName("MedicationRequest with contained medicationReference resolves code for PlanDefinition lookup")
  void medicationReference_isResolvedBeforeCodeLookup() {
    Medication medication = new Medication();
    medication.setId("med-1");
    medication.setCode(new CodeableConcept().addCoding(new Coding()
        .setSystem("http://www.nlm.nih.gov/research/umls/rxnorm")
        .setCode("197361")));

    MedicationRequest order = new MedicationRequest();
    order.setId("MedicationRequest/mr-1");
    order.setStatus(MedicationRequest.MedicationRequestStatus.DRAFT);
    order.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
    order.addContained(medication);
    order.setMedication(new Reference("#med-1"));

    PlanDefinition plan = planDefinitionWithLibrary("pd-med", "TestMedRule");
    when(planDefinitionService.findPlanDefinitions(any(Coding.class), anyList(), isNull()))
        .thenReturn(List.of(plan));
    mockLibraryWithDataRequirements("TestMedRule", "MedicationRequest");

    String canonical = "http://example.org/Questionnaire/med-check";
    when(planDefinitionService.applyPlanDefinition(eq(plan), eq("pat-1"), any(Bundle.class), isNull()))
        .thenReturn(requestGroupWithQuestionnaires(canonical));

    Questionnaire questionnaire = questionnaire("q-med", canonical, "1.0.0");
    when(questionnaireDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of(questionnaire));

    DtrQuestionnaireResolver.ResolutionResult result = resolver.resolve(
        null, List.of(order), coverageWithContainedPayor());

    assertEquals(1, result.questionnaires().size());
    DtrQuestionnaireResolver.ResolvedQuestionnaire resolved = result.questionnaires().get(0);
    assertEquals(canonical + "|1.0.0", resolved.canonical());
    assertEquals(DtrQuestionnaireResolver.ResolutionPath.ORDER, resolved.path());
    assertTrue(resolved.sourceOrderIds().stream().anyMatch(id -> id.contains("mr-1")));

    ArgumentCaptor<Coding> codingCaptor = ArgumentCaptor.forClass(Coding.class);
    verify(planDefinitionService).findPlanDefinitions(codingCaptor.capture(), anyList(), isNull());
    assertEquals("197361", codingCaptor.getValue().getCode());
  }

  @Test
  @DisplayName("SupplyRequest with contained itemReference resolves code for PlanDefinition lookup")
  void supplyRequestItemReference_isResolvedBeforeCodeLookup() {
    Medication medication = new Medication();
    medication.setId("med-supply-1");
    medication.setCode(new CodeableConcept().addCoding(new Coding()
        .setSystem("http://www.nlm.nih.gov/research/umls/rxnorm")
        .setCode("123456")));

    SupplyRequest order = new SupplyRequest();
    order.setId("SupplyRequest/sr-1");
    order.setStatus(SupplyRequest.SupplyRequestStatus.ACTIVE);
    order.addContained(medication);
    order.setItem(new Reference("#med-supply-1"));

    PlanDefinition plan = planDefinitionWithLibrary("pd-supply", "TestSupplyRule");
    when(planDefinitionService.findPlanDefinitions(any(Coding.class), anyList(), isNull()))
        .thenReturn(List.of(plan));
    mockLibraryWithDataRequirements("TestSupplyRule"); // no patient-queryable types

    String canonical = "http://example.org/Questionnaire/supply-check";
    when(planDefinitionService.applyPlanDefinition(eq(plan), eq("pat-1"), any(Bundle.class), isNull()))
        .thenReturn(requestGroupWithQuestionnaires(canonical));

    Questionnaire questionnaire = questionnaire("q-supply", canonical, "1.0.0");
    when(questionnaireDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of(questionnaire));

    DtrQuestionnaireResolver.ResolutionResult result = resolver.resolve(
        null, List.of(order), coverageWithContainedPayor());

    assertEquals(1, result.questionnaires().size());
    DtrQuestionnaireResolver.ResolvedQuestionnaire resolved = result.questionnaires().get(0);
    assertEquals(canonical + "|1.0.0", resolved.canonical());
    assertEquals(DtrQuestionnaireResolver.ResolutionPath.ORDER, resolved.path());
    assertTrue(resolved.sourceOrderIds().stream().anyMatch(id -> id.contains("sr-1")));

    ArgumentCaptor<Coding> codingCaptor = ArgumentCaptor.forClass(Coding.class);
    verify(planDefinitionService).findPlanDefinitions(codingCaptor.capture(), anyList(), isNull());
    assertEquals("123456", codingCaptor.getValue().getCode());
  }

  @Test
  @DisplayName("Coverage-info questionnaires are collected from all RequestGroup actions")
  void coverageInfo_isCollectedAcrossAllActions() {
    DeviceRequest order = new DeviceRequest();
    order.setId("DeviceRequest/dr-1");
    order.setCode(new CodeableConcept().addCoding(new Coding()
        .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
        .setCode("E0424")));

    PlanDefinition plan = planDefinitionWithLibrary("pd-1", "TestRule");
    when(planDefinitionService.findPlanDefinitions(any(Coding.class), anyList(), isNull()))
        .thenReturn(List.of(plan));
    mockLibraryWithDataRequirements("TestRule"); // no patient-queryable types

    String canonicalOne = "http://example.org/Questionnaire/q-one";
    String canonicalTwo = "http://example.org/Questionnaire/q-two";
    when(planDefinitionService.applyPlanDefinition(eq(plan), eq("pat-1"), any(Bundle.class), isNull()))
        .thenReturn(requestGroupWithQuestionnaires(canonicalOne, canonicalTwo));

    Questionnaire qOne = questionnaire("q-1", canonicalOne, "1.0.0");
    Questionnaire qTwo = questionnaire("q-2", canonicalTwo, "2.0.0");
    when(questionnaireDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of(qOne), List.of(qTwo));

    DtrQuestionnaireResolver.ResolutionResult result = resolver.resolve(
        null, List.of(order), coverageWithContainedPayor());

    assertEquals(2, result.questionnaires().size());
    List<String> canonicals = result.questionnaires().stream()
        .map(DtrQuestionnaireResolver.ResolvedQuestionnaire::canonical)
        .toList();
    assertTrue(canonicals.contains(canonicalOne + "|1.0.0"));
    assertTrue(canonicals.contains(canonicalTwo + "|2.0.0"));
  }

  @Test
  @DisplayName("Absolute beneficiary reference is preserved for clinical data subject search")
  void absoluteBeneficiaryReference_preservedForClinicalDataFetch() {
    when(daoRegistry.getResourceDao("Procedure")).thenReturn(procedureDao);
    when(procedureDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of());

    DeviceRequest order = new DeviceRequest();
    order.setId("DeviceRequest/dr-abs");
    order.setCode(new CodeableConcept().addCoding(new Coding()
        .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
        .setCode("E0424")));

    PlanDefinition plan = planDefinitionWithLibrary("pd-abs", "TestAbsRule");
    when(planDefinitionService.findPlanDefinitions(any(Coding.class), anyList(), isNull()))
        .thenReturn(List.of(plan));
    mockLibraryWithDataRequirements("TestAbsRule", "Procedure");

    String canonical = "http://example.org/Questionnaire/q-abs";
    when(planDefinitionService.applyPlanDefinition(eq(plan), eq("pat-abs"), any(Bundle.class), isNull()))
        .thenReturn(requestGroupWithQuestionnaires(canonical));

    Questionnaire questionnaire = questionnaire("q-abs", canonical, "1.0.0");
    when(questionnaireDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of(questionnaire));

    resolver.resolve(
        null, List.of(order), coverageWithContainedPayor("https://payer.example/fhir/Patient/pat-abs"));

    ArgumentCaptor<SearchParameterMap> mapCaptor = ArgumentCaptor.forClass(SearchParameterMap.class);
    verify(procedureDao).searchForResources(mapCaptor.capture(), any(SystemRequestDetails.class));

    SearchParameterMap searchMap = mapCaptor.getValue();
    assertTrue(searchMap.containsKey("subject"));
    var subjectParam = (ca.uhn.fhir.rest.param.ReferenceParam) searchMap.get("subject").get(0).get(0);
    assertEquals("https://payer.example/fhir", subjectParam.getBaseUrl());
    assertEquals("Patient", subjectParam.getResourceType());
    assertEquals("pat-abs", subjectParam.getIdPart());
  }

    @Test
    @DisplayName("External payor references are never read from the local store by id")
    void externalPayorReference_doesNotResolveFromLocalStore() {
        DeviceRequest order = new DeviceRequest();
        order.setId("DeviceRequest/dr-ext-payor");
        order.setCode(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
                .setCode("E0424")));

        Coverage coverage = new Coverage();
        coverage.setId("Coverage/cov-ext-payor");
        coverage.setBeneficiary(new Reference("Patient/pat-1"));
        coverage.addPayor(new Reference("Organization/org-external"));

        DtrQuestionnaireResolver.ResolutionResult result =
            resolver.resolve(null, List.of(order), coverage);

        verify(planDefinitionService, org.mockito.Mockito.never())
            .findPlanDefinitions(any(Coding.class), anyList(), isNull());
        assertTrue(result.warnings().stream()
            .anyMatch(w -> w.contains("No payor identifiers")));
    }

  @Test
  @DisplayName("Only dataRequirement-declared types trigger clinical data queries")
  @SuppressWarnings("rawtypes")
  void dataRequirementTypes_drivesClinicalDataFetch() {
    IFhirResourceDao conditionDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao("Condition")).thenReturn(conditionDao);
    when(daoRegistry.getResourceDao("Procedure")).thenReturn(procedureDao);

    when(conditionDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of());
    when(procedureDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of());

    DeviceRequest order = new DeviceRequest();
    order.setId("DeviceRequest/dr-data");
    order.setCode(new CodeableConcept().addCoding(new Coding()
        .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
        .setCode("E0424")));

    PlanDefinition plan = planDefinitionWithLibrary("pd-data", "TestDataRule");
    when(planDefinitionService.findPlanDefinitions(any(Coding.class), anyList(), isNull()))
        .thenReturn(List.of(plan));
    // Library declares Condition and Procedure -- only these should be queried
    mockLibraryWithDataRequirements("TestDataRule", "Condition", "Procedure", "Coverage");

    when(planDefinitionService.applyPlanDefinition(eq(plan), eq("pat-1"), any(Bundle.class), isNull()))
        .thenReturn(new RequestGroup());

    resolver.resolve(null, List.of(order), coverageWithContainedPayor());

    // Condition and Procedure are patient-queryable and should be fetched
    verify(conditionDao).searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class));
    verify(procedureDao).searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class));
  }

  @Test
  @DisplayName("Versioned library reference resolves for clinical data requirements")
  @SuppressWarnings("rawtypes")
  void versionedLibraryReference_resolvesForClinicalDataFetch() {
    IFhirResourceDao procedureDaoForVersionedLibrary = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao("Procedure")).thenReturn(procedureDaoForVersionedLibrary);

    when(procedureDaoForVersionedLibrary.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of());

    DeviceRequest order = new DeviceRequest();
    order.setId("DeviceRequest/dr-versioned");
    order.setCode(new CodeableConcept().addCoding(new Coding()
        .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
        .setCode("E0424")));

    PlanDefinition plan = new PlanDefinition();
    plan.setId("PlanDefinition/pd-versioned");
    plan.addLibrary("Library/VersionedRule|1.0.0");
    when(planDefinitionService.findPlanDefinitions(any(Coding.class), anyList(), isNull()))
        .thenReturn(List.of(plan));

    Library versionedLibrary = new Library();
    versionedLibrary.setId("Library/VersionedRule");
    versionedLibrary.addDataRequirement().setType("Procedure");
    when(libraryDao.read(any(IdType.class), any(SystemRequestDetails.class)))
        .thenAnswer(invocation -> {
          IdType requestedId = invocation.getArgument(0);
          if ("VersionedRule".equals(requestedId.getIdPart())) {
            return versionedLibrary;
          }
          throw new RuntimeException("Library not found for id " + requestedId.getValue());
        });

    when(planDefinitionService.applyPlanDefinition(eq(plan), eq("pat-1"), any(Bundle.class), isNull()))
        .thenReturn(new RequestGroup());

    resolver.resolve(null, List.of(order), coverageWithContainedPayor());

    ArgumentCaptor<IdType> libraryIdCaptor = ArgumentCaptor.forClass(IdType.class);
    verify(libraryDao).read(libraryIdCaptor.capture(), any(SystemRequestDetails.class));
    assertEquals("VersionedRule", libraryIdCaptor.getValue().getIdPart());
    verify(procedureDaoForVersionedLibrary).searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class));
  }

  @Test
  @DisplayName("AllergyIntolerance and Immunization clinical prefetch uses patient search param")
  @SuppressWarnings("rawtypes")
  void allergyAndImmunization_usePatientSearchParam() {
    IFhirResourceDao allergyDao = mock(IFhirResourceDao.class);
    IFhirResourceDao immunizationDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao("AllergyIntolerance")).thenReturn(allergyDao);
    when(daoRegistry.getResourceDao("Immunization")).thenReturn(immunizationDao);

    when(allergyDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of());
    when(immunizationDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of());

    DeviceRequest order = new DeviceRequest();
    order.setId("DeviceRequest/dr-clinical");
    order.setCode(new CodeableConcept().addCoding(new Coding()
        .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
        .setCode("E0424")));

    PlanDefinition plan = planDefinitionWithLibrary("pd-clinical", "ClinicalRule");
    when(planDefinitionService.findPlanDefinitions(any(Coding.class), anyList(), isNull()))
        .thenReturn(List.of(plan));
    mockLibraryWithDataRequirements("ClinicalRule", "AllergyIntolerance", "Immunization");

    when(planDefinitionService.applyPlanDefinition(eq(plan), eq("pat-1"), any(Bundle.class), isNull()))
        .thenReturn(new RequestGroup());

    resolver.resolve(null, List.of(order), coverageWithContainedPayor());

    ArgumentCaptor<SearchParameterMap> allergySearchCaptor = ArgumentCaptor.forClass(SearchParameterMap.class);
    verify(allergyDao).searchForResources(allergySearchCaptor.capture(), any(SystemRequestDetails.class));
    SearchParameterMap allergySearch = allergySearchCaptor.getValue();
    assertTrue(allergySearch.containsKey("patient"));
    assertFalse(allergySearch.containsKey("subject"));

    ArgumentCaptor<SearchParameterMap> immunizationSearchCaptor = ArgumentCaptor.forClass(SearchParameterMap.class);
    verify(immunizationDao).searchForResources(immunizationSearchCaptor.capture(), any(SystemRequestDetails.class));
    SearchParameterMap immunizationSearch = immunizationSearchCaptor.getValue();
    assertTrue(immunizationSearch.containsKey("patient"));
    assertFalse(immunizationSearch.containsKey("subject"));
  }

  @Test
  @DisplayName("Libraries without dataRequirement do not trigger clinical data queries")
  void noDataRequirement_noClinicalDataFetch() {
    DeviceRequest order = new DeviceRequest();
    order.setId("DeviceRequest/dr-empty");
    order.setCode(new CodeableConcept().addCoding(new Coding()
        .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
        .setCode("E0424")));

    PlanDefinition plan = planDefinitionWithLibrary("pd-empty", "EmptyRule");
    when(planDefinitionService.findPlanDefinitions(any(Coding.class), anyList(), isNull()))
        .thenReturn(List.of(plan));
    // Library with no dataRequirement entries
    mockLibraryWithDataRequirements("EmptyRule");

    when(planDefinitionService.applyPlanDefinition(eq(plan), eq("pat-1"), any(Bundle.class), isNull()))
        .thenReturn(new RequestGroup());

    resolver.resolve(null, List.of(order), coverageWithContainedPayor());

    // No resource type DAOs should be queried for clinical data
    verify(procedureDao, never()).searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class));
  }

  @Test
  @DisplayName("Draft order excludes PlanDefinitions triggered only by order-dispatch")
  void draftOrder_excludesDispatchOnlyPlans() {
    DeviceRequest order = new DeviceRequest();
    order.setId("DeviceRequest/dr-draft");
    order.setStatus(DeviceRequest.DeviceRequestStatus.DRAFT);
    order.setCode(new CodeableConcept().addCoding(new Coding()
        .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
        .setCode("E0424")));

    PlanDefinition signPlan = planDefinitionWithLibrary("pd-sign", "SignRule");
    signPlan.addAction().addTrigger()
        .setType(TriggerDefinition.TriggerType.NAMEDEVENT)
        .setName("order-sign");
    PlanDefinition dispatchPlan = planDefinitionWithLibrary("pd-dispatch", "DispatchRule");
    dispatchPlan.addAction().addTrigger()
        .setType(TriggerDefinition.TriggerType.NAMEDEVENT)
        .setName("order-dispatch");
    when(planDefinitionService.findPlanDefinitions(any(Coding.class), anyList(), isNull()))
        .thenReturn(List.of(signPlan, dispatchPlan));
    mockLibraryWithDataRequirements("SignRule");

    String canonical = "http://example.org/Questionnaire/sign-check";
    when(planDefinitionService.applyPlanDefinition(eq(signPlan), eq("pat-1"), any(Bundle.class), isNull()))
        .thenReturn(requestGroupWithQuestionnaires(canonical));

    Questionnaire questionnaire = questionnaire("q-sign", canonical, "1.0.0");
    when(questionnaireDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of(questionnaire));

    DtrQuestionnaireResolver.ResolutionResult result = resolver.resolve(
        null, List.of(order), coverageWithContainedPayor());

    assertEquals(1, result.questionnaires().size());
    assertEquals(canonical + "|1.0.0", result.questionnaires().get(0).canonical());
    verify(planDefinitionService, never()).applyPlanDefinition(eq(dispatchPlan), any(), any(), any());
    assertTrue(result.warnings().stream()
        .anyMatch(w -> w.contains("pd-dispatch") && w.contains("order-dispatch stage")));
  }

  @Test
  @DisplayName("Questionnaire with completed QuestionnaireResponse on file for the order is excluded")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void completedResponseOnFile_excludesQuestionnaire() {
    IFhirResourceDao qrDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(QuestionnaireResponse.class)).thenReturn(qrDao);

    DeviceRequest order = new DeviceRequest();
    order.setId("DeviceRequest/dr-satisfied");
    order.setStatus(DeviceRequest.DeviceRequestStatus.ACTIVE);
    order.setCode(new CodeableConcept().addCoding(new Coding()
        .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
        .setCode("E0424")));

    PlanDefinition plan = planDefinitionWithLibrary("pd-satisfied", "SatisfiedRule");
    when(planDefinitionService.findPlanDefinitions(any(Coding.class), anyList(), isNull()))
        .thenReturn(List.of(plan));
    mockLibraryWithDataRequirements("SatisfiedRule");

    String canonical = "http://example.org/Questionnaire/satisfied-check";
    when(planDefinitionService.applyPlanDefinition(eq(plan), eq("pat-1"), any(Bundle.class), isNull()))
        .thenReturn(requestGroupWithQuestionnaires(canonical));

    Questionnaire questionnaire = questionnaire("q-satisfied", canonical, "1.0.0");
    when(questionnaireDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of(questionnaire));

    QuestionnaireResponse completedQr = new QuestionnaireResponse();
    completedQr.setId("QuestionnaireResponse/qr-1");
    completedQr.setStatus(QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED);
    completedQr.setQuestionnaire(canonical + "|1.0.0");
    completedQr.addExtension(new Extension(DtrConstants.QR_CONTEXT_EXT,
        new Reference("DeviceRequest/dr-satisfied")));
    when(qrDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of(completedQr));

    DtrQuestionnaireResolver.ResolutionResult result = resolver.resolve(
        null, List.of(order), coverageWithContainedPayor());

    assertTrue(result.questionnaires().isEmpty());
    assertTrue(result.warnings().stream()
        .anyMatch(w -> w.contains("already on file")));
  }

  @Test
  @DisplayName("Active order without performer excludes dispatch-only PlanDefinitions")
  void activeOrderWithoutPerformer_excludesDispatchOnlyPlans() {
    DeviceRequest order = new DeviceRequest();
    order.setId("DeviceRequest/dr-active");
    order.setStatus(DeviceRequest.DeviceRequestStatus.ACTIVE);
    order.setCode(new CodeableConcept().addCoding(new Coding()
        .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
        .setCode("E0424")));

    PlanDefinition dispatchPlan = planDefinitionWithLibrary("pd-dispatch", "DispatchRule");
    dispatchPlan.addAction().addTrigger()
        .setType(TriggerDefinition.TriggerType.NAMEDEVENT)
        .setName("order-dispatch");
    when(planDefinitionService.findPlanDefinitions(any(Coding.class), anyList(), isNull()))
        .thenReturn(List.of(dispatchPlan));

    DtrQuestionnaireResolver.ResolutionResult result = resolver.resolve(
        null, List.of(order), coverageWithContainedPayor());

    assertTrue(result.questionnaires().isEmpty());
    verify(planDefinitionService, never()).applyPlanDefinition(any(), any(), any(), any());
    assertTrue(result.warnings().stream()
        .anyMatch(w -> w.contains("pd-dispatch") && w.contains("order-dispatch stage")));
  }

  @Test
  @DisplayName("Active order with performer includes dispatch-only PlanDefinitions")
  void activeOrderWithPerformer_includesDispatchOnlyPlans() {
    DeviceRequest order = new DeviceRequest();
    order.setId("DeviceRequest/dr-dispatched");
    order.setStatus(DeviceRequest.DeviceRequestStatus.ACTIVE);
    order.setPerformer(new Reference("Organization/dme-supplier-1"));
    order.setCode(new CodeableConcept().addCoding(new Coding()
        .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
        .setCode("E0424")));

    PlanDefinition dispatchPlan = planDefinitionWithLibrary("pd-dispatch", "DispatchRule");
    dispatchPlan.addAction().addTrigger()
        .setType(TriggerDefinition.TriggerType.NAMEDEVENT)
        .setName("order-dispatch");
    when(planDefinitionService.findPlanDefinitions(any(Coding.class), anyList(), isNull()))
        .thenReturn(List.of(dispatchPlan));
    mockLibraryWithDataRequirements("DispatchRule");

    String canonical = "http://example.org/Questionnaire/dispatch-check";
    when(planDefinitionService.applyPlanDefinition(eq(dispatchPlan), eq("pat-1"), any(Bundle.class), isNull()))
        .thenReturn(requestGroupWithQuestionnaires(canonical));

    Questionnaire questionnaire = questionnaire("q-dispatch", canonical, "1.0.0");
    when(questionnaireDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of(questionnaire));

    DtrQuestionnaireResolver.ResolutionResult result = resolver.resolve(
        null, List.of(order), coverageWithContainedPayor());

    assertEquals(1, result.questionnaires().size());
    assertEquals(canonical + "|1.0.0", result.questionnaires().get(0).canonical());
  }

  @Test
  @DisplayName("resolveContextLaunch on a PAS TRN yields PAS_TRN provenance and recovers the pended order")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void resolveContextLaunch_pasTrn_recoversOrderAndCoverage() {
    String context = "home-o2-std-questionnaire";
    Questionnaire q = questionnaire(context,
        "http://example.org/fhir/Questionnaire/home-o2-std-questionnaire", "2.2.0");
    when(questionnaireDao.read(any(IdType.class), any(SystemRequestDetails.class))).thenReturn(q);

    IFhirResourceDao claimResponseDao = mock(IFhirResourceDao.class);
    IFhirResourceDao claimDao = mock(IFhirResourceDao.class);
    IFhirResourceDao deviceRequestDao = mock(IFhirResourceDao.class);
    IFhirResourceDao coverageDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(org.hl7.fhir.r4.model.ClaimResponse.class)).thenReturn(claimResponseDao);
    when(daoRegistry.getResourceDao(org.hl7.fhir.r4.model.Claim.class)).thenReturn(claimDao);
    when(daoRegistry.getResourceDao("DeviceRequest")).thenReturn(deviceRequestDao);
    when(daoRegistry.getResourceDao("Coverage")).thenReturn(coverageDao);

    org.hl7.fhir.r4.model.ClaimResponse cr = new org.hl7.fhir.r4.model.ClaimResponse();
    cr.setId("ClaimResponse/cr-1");
    cr.setRequest(new Reference("Claim/claim-1"));
    org.hl7.fhir.r4.model.ClaimResponse.ItemComponent crItem = cr.addItem();
    crItem.setItemSequence(1);
    crItem.addExtension(new Extension(
        "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-itemTraceNumber",
        new org.hl7.fhir.r4.model.Identifier()
            .setSystem("urn:trnorg:PASPAYER").setValue(context)));
    when(claimResponseDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of(cr));

    org.hl7.fhir.r4.model.Claim claim = new org.hl7.fhir.r4.model.Claim();
    claim.setId("Claim/claim-1");
    org.hl7.fhir.r4.model.Claim.ItemComponent claimItem = claim.addItem();
    claimItem.setSequence(1);
    claimItem.addExtension(new Extension(
        "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-requestedService",
        new Reference("DeviceRequest/dr-1")));
    claim.addInsurance().setCoverage(new Reference("Coverage/cov-1"));
    when(claimDao.read(any(IdType.class), any(SystemRequestDetails.class))).thenReturn(claim);

    DeviceRequest order = new DeviceRequest();
    order.setId("DeviceRequest/dr-1");
    when(deviceRequestDao.read(any(IdType.class), any(SystemRequestDetails.class))).thenReturn(order);
    Coverage coverage = new Coverage();
    coverage.setId("Coverage/cov-1");
    when(coverageDao.read(any(IdType.class), any(SystemRequestDetails.class))).thenReturn(coverage);

    DtrQuestionnaireResolver.ContextResolution result = resolver.resolveContextLaunch(context);

    assertEquals(DtrQuestionnaireResolver.DtrLaunchProvenance.PAS_TRN, result.provenance());
    assertEquals(1, result.orders().size());
    assertEquals("DeviceRequest/dr-1",
        result.orders().get(0).getIdElement().toUnqualifiedVersionless().getValue());
    assertNotNull(result.coverage());
  }

  @Test
  @DisplayName("resolveContextLaunch on a CRD assertion yields CRD_CONTEXT provenance and registry order")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  void resolveContextLaunch_crdContext_recoversOrderFromRegistry() {
    when(questionnaireDao.read(any(IdType.class), any(SystemRequestDetails.class)))
        .thenThrow(new ResourceNotFoundException("not found"));

    String canonical = "http://example.org/fhir/Questionnaire/home-o2-std-questionnaire";
    DeviceRequest registeredOrder = new DeviceRequest();
    registeredOrder.setId("DeviceRequest/dr-9");
    when(contextRegistry.lookup("CRD-abc-123")).thenReturn(java.util.Optional.of(
        new DtrContextRegistry.DtrContext(List.of(canonical), registeredOrder, new Coverage())));

    Questionnaire q = questionnaire("home-o2", canonical, "2.2.0");
    when(questionnaireDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(List.of(q));

    DtrQuestionnaireResolver.ContextResolution result = resolver.resolveContextLaunch("CRD-abc-123");

    assertEquals(DtrQuestionnaireResolver.DtrLaunchProvenance.CRD_CONTEXT, result.provenance());
    assertEquals(1, result.orders().size());
    assertEquals("DeviceRequest/dr-9",
        result.orders().get(0).getIdElement().toUnqualifiedVersionless().getValue());
  }

  private Coverage coverageWithContainedPayor() {
    return coverageWithContainedPayor("Patient/pat-1");
  }

  private Coverage coverageWithContainedPayor(String beneficiaryReference) {
    Coverage coverage = new Coverage();
    coverage.setId("Coverage/cov-1");
    coverage.setBeneficiary(new Reference(beneficiaryReference));

    Organization payor = new Organization();
    payor.setId("payor-org");
    payor.addIdentifier()
        .setSystem("urn:oid:2.16.840.1.113883.6.300")
        .setValue("00001");

    coverage.addContained(payor);
    coverage.addPayor(new Reference("#payor-org"));
    return coverage;
  }

  private Questionnaire questionnaire(String id, String url, String version) {
    Questionnaire questionnaire = new Questionnaire();
    questionnaire.setId(id);
    questionnaire.setUrl(url);
    questionnaire.setVersion(version);
    return questionnaire;
  }

  private RequestGroup requestGroupWithQuestionnaires(String... canonicals) {
    RequestGroup requestGroup = new RequestGroup();
    for (String canonical : canonicals) {
      RequestGroup.RequestGroupActionComponent action = requestGroup.addAction();
      Extension coverageInfo = new Extension(CrdConstants.COVERAGE_INFO_EXT);
      coverageInfo.addExtension(new Extension("questionnaire", new CanonicalType(canonical)));
      action.addExtension(coverageInfo);
    }
    return requestGroup;
  }

  private PlanDefinition planDefinitionWithLibrary(String id, String libraryName) {
    PlanDefinition plan = new PlanDefinition();
    plan.setId("PlanDefinition/" + id);
    plan.addLibrary("Library/" + libraryName);
    return plan;
  }

  private void mockLibraryWithDataRequirements(String libraryName, String... types) {
    Library library = new Library();
    library.setId("Library/" + libraryName);
    for (String type : types) {
      library.addDataRequirement().setType(type);
    }
    lenient().when(libraryDao.read(any(IdType.class), any(SystemRequestDetails.class)))
        .thenReturn(library);
  }
}
