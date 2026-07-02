package org.hl7.davinci.common;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.TriggerDefinition.TriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanDefinitionServiceTest {

  @Mock
  private DaoRegistry daoRegistry;

  @Mock
  @SuppressWarnings("rawtypes")
  private IFhirResourceDao planDefinitionDao;

  @InjectMocks
  private PlanDefinitionService planDefinitionService;

  private Coding testCode;
  private List<Identifier> payorIdentifiers;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    testCode = new Coding()
        .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
        .setCode("E0424");

    payorIdentifiers = List.of(
        new Identifier().setSystem("urn:oid:2.16.840.1.113883.6.300").setValue("00001"));

    lenient().when(daoRegistry.getResourceDao(PlanDefinition.class)).thenReturn(planDefinitionDao);
  }

  private PlanDefinition createPlanDefinition(String id, String triggerName) {
    PlanDefinition planDef = new PlanDefinition();
    planDef.setId(id);

    if (triggerName != null) {
      PlanDefinition.PlanDefinitionActionComponent action = planDef.addAction();
      action.addTrigger()
          .setType(TriggerType.NAMEDEVENT)
          .setName(triggerName);
    }

    return planDef;
  }

  @Nested
  @DisplayName("Null hook (DTR path)")
  class NullHookTests {

    @Test
    @DisplayName("Returns all code+payor matches regardless of trigger type")
    void nullHook_returnsAllMatches() {
      PlanDefinition planWithOrderSign = createPlanDefinition("pd-1", "order-sign");
      PlanDefinition planWithOrderSelect = createPlanDefinition("pd-2", "order-select");
      PlanDefinition planWithNoAction = new PlanDefinition();
      planWithNoAction.setId("pd-3");

      when(planDefinitionDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
          .thenReturn(List.of(planWithOrderSign, planWithOrderSelect, planWithNoAction));

      List<PlanDefinition> results = planDefinitionService.findPlanDefinitions(testCode, payorIdentifiers, null);

      assertEquals(3, results.size(), "Null hook should return all PlanDefinitions without trigger filtering");
    }
  }

  @Nested
  @DisplayName("Non-null hook (CRD path)")
  class NonNullHookTests {

    @Test
    @DisplayName("Filters by trigger name when hook is specified")
    void nonNullHook_filtersByTrigger() {
      PlanDefinition planWithOrderSign = createPlanDefinition("pd-1", "order-sign");
      PlanDefinition planWithOrderSelect = createPlanDefinition("pd-2", "order-select");

      when(planDefinitionDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
          .thenReturn(List.of(planWithOrderSign, planWithOrderSelect));

      List<PlanDefinition> results = planDefinitionService.findPlanDefinitions(testCode, payorIdentifiers, "order-sign");

      assertEquals(1, results.size());
      assertEquals("pd-1", results.get(0).getId());
    }

    @Test
    @DisplayName("Returns empty list when no triggers match")
    void nonNullHook_noMatches() {
      PlanDefinition planWithOrderSign = createPlanDefinition("pd-1", "order-sign");

      when(planDefinitionDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
          .thenReturn(List.of(planWithOrderSign));

      List<PlanDefinition> results = planDefinitionService.findPlanDefinitions(testCode, payorIdentifiers, "encounter-start");

      assertTrue(results.isEmpty());
    }
  }

  @Nested
  @DisplayName("Search parameter construction")
  class SearchParameterConstructionTests {

    @Test
    @DisplayName("findPlanDefinitions uses context-type + context token params (no composite context-type-value)")
    void findPlanDefinitions_usesTokenParams() {
      when(planDefinitionDao.searchForResources(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
          .thenReturn(List.of());

      planDefinitionService.findPlanDefinitions(testCode, payorIdentifiers, "order-sign");

      ArgumentCaptor<SearchParameterMap> mapCaptor = ArgumentCaptor.forClass(SearchParameterMap.class);
      verify(planDefinitionDao).searchForResources(mapCaptor.capture(), any(SystemRequestDetails.class));

      SearchParameterMap searchMap = mapCaptor.getValue();
      assertTrue(searchMap.containsKey("context-type"));
      assertTrue(searchMap.containsKey("context"));
      assertFalse(searchMap.containsKey("context-type-value"));

      assertEquals(2, searchMap.get("context-type").size(), "Should require both focus and program context types");
      assertEquals(2, searchMap.get("context").size(), "Should AND code-match group with payor-match group");
      assertEquals(2, searchMap.get("context").get(0).size(), "Code OR list should include http/https variants");
      assertEquals(1, searchMap.get("context").get(1).size(), "Payor OR list should include all identifiers");
    }

    @Test
    @DisplayName("isPayorHandled uses program context-type + context token OR list")
    void isPayorHandled_usesTokenParams() {
      when(planDefinitionDao.searchForIds(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
          .thenReturn(List.of(new IdType("PlanDefinition/pd-1")));

      boolean handled = planDefinitionService.isPayorHandled(payorIdentifiers);
      assertTrue(handled);

      ArgumentCaptor<SearchParameterMap> mapCaptor = ArgumentCaptor.forClass(SearchParameterMap.class);
      verify(planDefinitionDao).searchForIds(mapCaptor.capture(), any(SystemRequestDetails.class));

      SearchParameterMap searchMap = mapCaptor.getValue();
      assertTrue(searchMap.containsKey("context-type"));
      assertTrue(searchMap.containsKey("context"));
      assertFalse(searchMap.containsKey("context-type-value"));

      assertEquals(1, searchMap.get("context-type").size(), "Payor-handled check requires program context type only");
      assertEquals(1, searchMap.get("context").size(), "Payor-handled check should OR over payor identifiers");
      assertEquals(1, searchMap.get("context").get(0).size(), "Single payor identifier in test fixture");
    }
  }

  @Nested
  @DisplayName("extractRequestGroup")
  class ExtractRequestGroupTests {

    @Test
    @DisplayName("Returns null for null input")
    void returnsNull_forNullInput() {
      assertNull(planDefinitionService.extractRequestGroup(null));
    }

    @Test
    @DisplayName("Extracts RequestGroup from R4 CarePlan contained resource")
    void extractsFromCarePlan() {
      RequestGroup rg = new RequestGroup();
      rg.setId("rg-1");

      CarePlan carePlan = new CarePlan();
      carePlan.addContained(rg);
      CarePlan.CarePlanActivityComponent activity = carePlan.addActivity();
      activity.getReference().setReference("#rg-1");

      RequestGroup result = planDefinitionService.extractRequestGroup(carePlan);

      assertNotNull(result);
      assertEquals("rg-1", result.getIdElement().getIdPart());
    }

    @Test
    @DisplayName("Returns null from CarePlan without activities")
    void returnsNull_fromCarePlanWithoutActivities() {
      CarePlan carePlan = new CarePlan();
      assertNull(planDefinitionService.extractRequestGroup(carePlan));
    }

    @Test
    @DisplayName("Extracts RequestGroup from R5 Parameters return")
    void extractsFromParameters() {
      RequestGroup rg = new RequestGroup();
      rg.setId("rg-1");

      Bundle bundle = new Bundle();
      bundle.addEntry().setResource(rg);

      Parameters params = new Parameters();
      params.addParameter().setName("return").setResource(bundle);

      RequestGroup result = planDefinitionService.extractRequestGroup(params);

      assertNotNull(result);
      assertEquals("rg-1", result.getIdElement().getIdPart());
    }
  }

  @Nested
  @DisplayName("removeDispatchPlansLackingEvidence")
  class RemoveDispatchPlansTests {

    @Test
    @DisplayName("Draft order removes dispatch-only plans and returns a note")
    void draftOrder_removesDispatchOnly() {
      DeviceRequest order = new DeviceRequest();
      order.setId("DeviceRequest/dr-1");
      order.setStatus(DeviceRequest.DeviceRequestStatus.DRAFT);

      List<PlanDefinition> plans = new ArrayList<>(List.of(
          createPlanDefinition("pd-sign", "order-sign"),
          createPlanDefinition("pd-dispatch", "order-dispatch")));

      List<String> notes = planDefinitionService.removeDispatchPlansLackingEvidence(plans, order);

      assertEquals(1, plans.size());
      assertEquals("pd-sign", plans.get(0).getIdElement().getIdPart());
      assertEquals(1, notes.size());
      assertTrue(notes.get(0).contains("pd-dispatch"));
    }

    @Test
    @DisplayName("Active order without performer removes dispatch-only plans")
    void activeOrderWithoutPerformer_removesDispatchOnly() {
      DeviceRequest order = new DeviceRequest();
      order.setId("DeviceRequest/dr-1");
      order.setStatus(DeviceRequest.DeviceRequestStatus.ACTIVE);

      List<PlanDefinition> plans = new ArrayList<>(
          List.of(createPlanDefinition("pd-dispatch", "order-dispatch")));

      planDefinitionService.removeDispatchPlansLackingEvidence(plans, order);

      assertTrue(plans.isEmpty());
    }

    @Test
    @DisplayName("Active order with performer keeps dispatch-only plans")
    void activeOrderWithPerformer_keepsDispatchOnly() {
      DeviceRequest order = new DeviceRequest();
      order.setId("DeviceRequest/dr-1");
      order.setStatus(DeviceRequest.DeviceRequestStatus.ACTIVE);
      order.setPerformer(new Reference("Organization/dme-supplier-1"));

      List<PlanDefinition> plans = new ArrayList<>(
          List.of(createPlanDefinition("pd-dispatch", "order-dispatch")));

      List<String> notes = planDefinitionService.removeDispatchPlansLackingEvidence(plans, order);

      assertEquals(1, plans.size());
      assertTrue(notes.isEmpty());
    }

    @Test
    @DisplayName("SupplyRequest supplier counts as a performer")
    void supplyRequestSupplier_countsAsPerformer() {
      SupplyRequest order = new SupplyRequest();
      order.setId("SupplyRequest/sr-1");
      order.setStatus(SupplyRequest.SupplyRequestStatus.ACTIVE);
      order.addSupplier(new Reference("Organization/dme-supplier-1"));

      List<PlanDefinition> plans = new ArrayList<>(
          List.of(createPlanDefinition("pd-dispatch", "order-dispatch")));

      planDefinitionService.removeDispatchPlansLackingEvidence(plans, order);

      assertEquals(1, plans.size());
    }

    @Test
    @DisplayName("Null order removes dispatch-only plans")
    void nullOrder_removesDispatchOnly() {
      List<PlanDefinition> plans = new ArrayList<>(
          List.of(createPlanDefinition("pd-dispatch", "order-dispatch")));

      List<String> notes = planDefinitionService.removeDispatchPlansLackingEvidence(plans, null);

      assertTrue(plans.isEmpty());
      assertEquals(1, notes.size());
    }

    @Test
    @DisplayName("Plans with mixed or no named-event triggers are never removed")
    void mixedTriggerPlans_areKept() {
      PlanDefinition mixed = createPlanDefinition("pd-mixed", "order-dispatch");
      mixed.addAction().addTrigger()
          .setType(TriggerType.NAMEDEVENT)
          .setName("order-sign");

      List<PlanDefinition> plans = new ArrayList<>(List.of(
          mixed,
          createPlanDefinition("pd-no-trigger", null)));

      planDefinitionService.removeDispatchPlansLackingEvidence(plans, null);

      assertEquals(2, plans.size());
    }
  }
}
