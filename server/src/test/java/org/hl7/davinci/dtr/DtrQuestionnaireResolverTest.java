package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.hl7.davinci.common.FhirUtil;
import org.hl7.davinci.common.PlanDefinitionService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Medication;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.SupplyRequest;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RequestGroup;
import org.hl7.fhir.r4.model.Resource;
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
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;

@ExtendWith(MockitoExtension.class)
class DtrQuestionnaireResolverTest {

  @Mock
  private DaoRegistry daoRegistry;

  @Mock
  private PlanDefinitionService planDefinitionService;

  @Mock
  @SuppressWarnings("rawtypes")
  private IFhirResourceDao patientDao;

  @Mock
  @SuppressWarnings("rawtypes")
  private IFhirResourceDao questionnaireDao;

  @Mock
  @SuppressWarnings("rawtypes")
  private IFhirResourceDao medicationDao;

  private DtrQuestionnaireResolver resolver;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    resolver = new DtrQuestionnaireResolver(daoRegistry, planDefinitionService);

    when(daoRegistry.getResourceDao(Patient.class)).thenReturn(patientDao);
    when(daoRegistry.getResourceDao(Questionnaire.class)).thenReturn(questionnaireDao);
    when(patientDao.read(any(IdType.class), any(SystemRequestDetails.class)))
        .thenReturn(new Patient().setId("Patient/pat-1"));
  }

  @Test
  @DisplayName("MedicationRequest with medicationReference resolves code for PlanDefinition lookup")
  @SuppressWarnings("unchecked")
  void medicationReference_isResolvedBeforeCodeLookup() {
    when(daoRegistry.getResourceDao("Medication")).thenReturn(medicationDao);

    Medication medication = new Medication();
    medication.setId("Medication/med-1");
    medication.setCode(new CodeableConcept().addCoding(new Coding()
        .setSystem("http://www.nlm.nih.gov/research/umls/rxnorm")
        .setCode("197361")));
    when(medicationDao.read(any(IdType.class), any(SystemRequestDetails.class))).thenReturn(medication);

    MedicationRequest order = new MedicationRequest();
    order.setId("MedicationRequest/mr-1");
    order.setStatus(MedicationRequest.MedicationRequestStatus.DRAFT);
    order.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
    order.setMedication(new Reference("Medication/med-1"));

    PlanDefinition plan = new PlanDefinition();
    plan.setId("PlanDefinition/pd-med");
    when(planDefinitionService.findPlanDefinitions(any(Coding.class), anyList(), isNull()))
        .thenReturn(List.of(plan));

    String canonical = "http://example.org/Questionnaire/med-check";
    when(planDefinitionService.applyPlanDefinition(eq(plan), eq("pat-1"), any(Bundle.class), isNull()))
        .thenReturn(requestGroupWithQuestionnaires(canonical));

    Questionnaire questionnaire = questionnaire("q-med", canonical, "1.0.0");
    IBundleProvider qProvider = bundleWith(questionnaire);
    when(questionnaireDao.search(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(qProvider);

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
  @DisplayName("SupplyRequest with itemReference resolves code for PlanDefinition lookup")
  @SuppressWarnings("unchecked")
  void supplyRequestItemReference_isResolvedBeforeCodeLookup() {
    when(daoRegistry.getResourceDao("Medication")).thenReturn(medicationDao);

    Medication medication = new Medication();
    medication.setId("Medication/med-supply-1");
    medication.setCode(new CodeableConcept().addCoding(new Coding()
        .setSystem("http://www.nlm.nih.gov/research/umls/rxnorm")
        .setCode("123456")));
    when(medicationDao.read(any(IdType.class), any(SystemRequestDetails.class))).thenReturn(medication);

    SupplyRequest order = new SupplyRequest();
    order.setId("SupplyRequest/sr-1");
    order.setStatus(SupplyRequest.SupplyRequestStatus.ACTIVE);
    order.setItem(new Reference("Medication/med-supply-1"));

    PlanDefinition plan = new PlanDefinition();
    plan.setId("PlanDefinition/pd-supply");
    when(planDefinitionService.findPlanDefinitions(any(Coding.class), anyList(), isNull()))
        .thenReturn(List.of(plan));

    String canonical = "http://example.org/Questionnaire/supply-check";
    when(planDefinitionService.applyPlanDefinition(eq(plan), eq("pat-1"), any(Bundle.class), isNull()))
        .thenReturn(requestGroupWithQuestionnaires(canonical));

    Questionnaire questionnaire = questionnaire("q-supply", canonical, "1.0.0");
    IBundleProvider qProvider = bundleWith(questionnaire);
    when(questionnaireDao.search(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(qProvider);

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

    PlanDefinition plan = new PlanDefinition();
    plan.setId("PlanDefinition/pd-1");
    when(planDefinitionService.findPlanDefinitions(any(Coding.class), anyList(), isNull()))
        .thenReturn(List.of(plan));

    String canonicalOne = "http://example.org/Questionnaire/q-one";
    String canonicalTwo = "http://example.org/Questionnaire/q-two";
    when(planDefinitionService.applyPlanDefinition(eq(plan), eq("pat-1"), any(Bundle.class), isNull()))
        .thenReturn(requestGroupWithQuestionnaires(canonicalOne, canonicalTwo));

    Questionnaire qOne = questionnaire("q-1", canonicalOne, "1.0.0");
    Questionnaire qTwo = questionnaire("q-2", canonicalTwo, "2.0.0");
    IBundleProvider qOneProvider = bundleWith(qOne);
    IBundleProvider qTwoProvider = bundleWith(qTwo);
    when(questionnaireDao.search(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(qOneProvider, qTwoProvider);

    DtrQuestionnaireResolver.ResolutionResult result = resolver.resolve(
        null, List.of(order), coverageWithContainedPayor());

    assertEquals(2, result.questionnaires().size());
    List<String> canonicals = result.questionnaires().stream()
        .map(DtrQuestionnaireResolver.ResolvedQuestionnaire::canonical)
        .toList();
    assertTrue(canonicals.contains(canonicalOne + "|1.0.0"));
    assertTrue(canonicals.contains(canonicalTwo + "|2.0.0"));
  }

  private Coverage coverageWithContainedPayor() {
    Coverage coverage = new Coverage();
    coverage.setId("Coverage/cov-1");
    coverage.setBeneficiary(new Reference("Patient/pat-1"));

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
      Extension coverageInfo = new Extension(FhirUtil.COVERAGE_INFO_EXT_URL);
      coverageInfo.addExtension(new Extension("questionnaire", new CanonicalType(canonical)));
      action.addExtension(coverageInfo);
    }
    return requestGroup;
  }

  private IBundleProvider bundleWith(Resource resource) {
    IBundleProvider bundleProvider = mock(IBundleProvider.class);
    when(bundleProvider.isEmpty()).thenReturn(false);
    when(bundleProvider.getResources(anyInt(), anyInt()))
        .thenReturn(List.of(resource));
    return bundleProvider;
  }
}
