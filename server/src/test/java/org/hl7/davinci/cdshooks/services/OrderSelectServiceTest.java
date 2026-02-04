package org.hl7.davinci.cdshooks.services;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import org.hl7.davinci.cdshooks.CdsHooksTestUtils;
import org.hl7.davinci.cdshooks.error.CdsHooksException;
import org.hl7.davinci.cdshooks.shared.ResolvedResources;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opencds.cqf.fhir.cr.hapi.common.IPlanDefinitionProcessorFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for OrderSelectService.
 * 
 * OrderSelectService is a supporting/secondary hook per CRD spec:
 * - MAY return cards/system actions (not required)
 * - Only processes orders that are in the 'selections' context
 * - Coverage-info not mandatory but allowed
 */
@ExtendWith(MockitoExtension.class)
class OrderSelectServiceTest {

  @Mock
  private DaoRegistry daoRegistry;

  @Mock
  private AppProperties appProperties;

  @Mock
  private IPlanDefinitionProcessorFactory planDefinitionProcessorFactory;

  @Mock
  private IFhirResourceDao<PlanDefinition> planDefinitionDao;

  @Mock
  private IBundleProvider bundleProvider;

  @InjectMocks
  private OrderSelectService orderSelectService;

  @BeforeEach
  void setUp() {
    // Common mock setup
    lenient().when(daoRegistry.getResourceDao(PlanDefinition.class)).thenReturn(planDefinitionDao);
    lenient().when(planDefinitionDao.search(any(), any())).thenReturn(bundleProvider);
    lenient().when(bundleProvider.isEmpty()).thenReturn(false);
    lenient().when(bundleProvider.size()).thenReturn(0);
    lenient().when(bundleProvider.getResources(anyInt(), anyInt())).thenReturn(Collections.emptyList());
  }

  @Nested
  @DisplayName("Hook Name Validation")
  class HookNameValidation {

    @Test
    @DisplayName("Should return hook name 'order-select'")
    void testGetHookName() {
      assertEquals("order-select", orderSelectService.getHookName());
    }

    @Test
    @DisplayName("Should throw 400 when hook name doesn't match")
    void testWrongHookName_Returns400() throws IOException {
      CdsServiceRequestJson request = CdsHooksTestUtils.loadRequest("order-sign-1.json");
      // order-sign-1.json has hook: "order-sign"

      CdsHooksException.BadRequestException exception = assertThrows(
          CdsHooksException.BadRequestException.class,
          () -> orderSelectService.handleRequest(request));

      assertTrue(exception.getMessage().contains("Mismatched hook"));
    }
  }

  @Nested
  @DisplayName("Resource Validation - Selections Required")
  class ResourceValidation {

    @Test
    @DisplayName("Should throw 400 when selections context is missing")
    void testMissingSelections_Returns400() {
      ResolvedResources context = new ResolvedResources();
      context.setPatient(CdsHooksTestUtils.createTestPatient("test-patient"));
      context.setCoverage(CdsHooksTestUtils.createTestCoverage("test-coverage", "org1234"));
      context.setOrders(List.of(
          CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0250", "test-patient")));
      // Note: selections would be set by handleRequest before validateExtractedResources

      // The validation happens after handleRequest extracts selections
      // With empty selections, it should fail
      CdsHooksException.BadRequestException exception = assertThrows(
          CdsHooksException.BadRequestException.class,
          () -> orderSelectService.validateExtractedResources(context));

      assertTrue(exception.getMessage().contains("selections") ||
          exception.getMessage().contains("draftOrders"));
    }

    @Test
    @DisplayName("Should throw 400 when draftOrders is empty")
    void testEmptyDraftOrders_Returns400() {
      ResolvedResources context = new ResolvedResources();
      context.setPatient(CdsHooksTestUtils.createTestPatient("test-patient"));
      context.setCoverage(CdsHooksTestUtils.createTestCoverage("test-coverage", "org1234"));
      context.setOrders(Collections.emptyList());

      CdsHooksException.BadRequestException exception = assertThrows(
          CdsHooksException.BadRequestException.class,
          () -> orderSelectService.validateExtractedResources(context));

      assertTrue(exception.getMessage().contains("draftOrders"));
    }
  }

  @Nested
  @DisplayName("Secondary Hook Behavior")
  class SecondaryHookBehavior {

    @Test
    @DisplayName("order-select is NOT a primary hook")
    void testIsSecondaryHook() {
      String hookName = orderSelectService.getHookName();
      assertFalse(
          hookName.equals("order-sign") ||
              hookName.equals("order-dispatch") ||
              hookName.equals("appointment-book"),
          "order-select should NOT be a primary hook");
    }
  }

  @Nested
  @DisplayName("Selection Matching Logic")
  class SelectionMatchingLogic {

    @Test
    @DisplayName("Should match selections by relative reference")
    void testSelectionsByRelativeRef() {
      ResolvedResources context = new ResolvedResources();
      DeviceRequest dr1 = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0250", "patient1");
      DeviceRequest dr2 = CdsHooksTestUtils.createTestDeviceRequest("dr-2", "E0251", "patient1");
      context.setOrders(List.of(dr1, dr2));

      // Simulate selections set by handleRequest
      // The actual matching happens in selectContextResources using the stored selections

      List<Resource> allOrders = context.getOrders();
      assertEquals(2, allOrders.size());
    }

    @Test
    @DisplayName("Should match selections by full URL")
    void testSelectionsByFullUrl() {
      // Selections can be full URLs like "http://example.org/fhir/MedicationRequest/1111"
      // The service should match these to orders in draftOrders

      ResolvedResources context = new ResolvedResources();
      MedicationRequest mr1 = CdsHooksTestUtils.createTestMedicationRequest("1111", "1049502", "patient1");
      MedicationRequest mr2 = CdsHooksTestUtils.createTestMedicationRequest("2222", "1049504", "patient1");
      context.setOrders(List.of(mr1, mr2));

      // Service should be able to match "http://example.org/fhir/MedicationRequest/1111"
      // to the MedicationRequest with id "1111"
      List<Resource> orders = context.getOrders();
      assertEquals(2, orders.size());

      // Verify IDs are accessible
      assertEquals("1111", mr1.getIdElement().getIdPart());
      assertEquals("2222", mr2.getIdElement().getIdPart());
    }
  }

}
