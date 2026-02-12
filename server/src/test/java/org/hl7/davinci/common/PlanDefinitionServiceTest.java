package org.hl7.davinci.common;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
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

  @Mock
  private IBundleProvider bundleProvider;

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
    lenient().when(planDefinitionDao.search(any(SearchParameterMap.class), any(SystemRequestDetails.class)))
        .thenReturn(bundleProvider);
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

      List<IBaseResource> resources = List.of(planWithOrderSign, planWithOrderSelect, planWithNoAction);
      when(bundleProvider.size()).thenReturn(resources.size());
      when(bundleProvider.getResources(0, resources.size())).thenReturn(resources);

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

      List<IBaseResource> resources = List.of(planWithOrderSign, planWithOrderSelect);
      when(bundleProvider.size()).thenReturn(resources.size());
      when(bundleProvider.getResources(0, resources.size())).thenReturn(resources);

      List<PlanDefinition> results = planDefinitionService.findPlanDefinitions(testCode, payorIdentifiers, "order-sign");

      assertEquals(1, results.size());
      assertEquals("pd-1", results.get(0).getId());
    }

    @Test
    @DisplayName("Returns empty list when no triggers match")
    void nonNullHook_noMatches() {
      PlanDefinition planWithOrderSign = createPlanDefinition("pd-1", "order-sign");

      List<IBaseResource> resources = List.of(planWithOrderSign);
      when(bundleProvider.size()).thenReturn(resources.size());
      when(bundleProvider.getResources(0, resources.size())).thenReturn(resources);

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
      when(bundleProvider.size()).thenReturn(0);
      when(bundleProvider.getResources(0, 0)).thenReturn(List.of());

      planDefinitionService.findPlanDefinitions(testCode, payorIdentifiers, "order-sign");

      ArgumentCaptor<SearchParameterMap> mapCaptor = ArgumentCaptor.forClass(SearchParameterMap.class);
      verify(planDefinitionDao).search(mapCaptor.capture(), any(SystemRequestDetails.class));

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
      when(bundleProvider.isEmpty()).thenReturn(false);

      boolean handled = planDefinitionService.isPayorHandled(payorIdentifiers);
      assertTrue(handled);

      ArgumentCaptor<SearchParameterMap> mapCaptor = ArgumentCaptor.forClass(SearchParameterMap.class);
      verify(planDefinitionDao).search(mapCaptor.capture(), any(SystemRequestDetails.class));

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
}
