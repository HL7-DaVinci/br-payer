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
 * Unit tests for OrderSignService.
 * 
 * These tests verify the service's validation logic, error handling,
 * and conformance to CRD specification requirements without requiring
 * full CQL execution or database access.
 */
@ExtendWith(MockitoExtension.class)
class OrderSignServiceTest {

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
  private OrderSignService orderSignService;

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
    @DisplayName("Should throw 400 when hook name doesn't match")
    void testWrongHookName_Returns400() throws IOException {
      CdsServiceRequestJson request = CdsHooksTestUtils.loadGeneratedRequest(
          "order-select", "hospital-beds-and-accessories-order-select.json");

      CdsHooksException.BadRequestException exception = assertThrows(
          CdsHooksException.BadRequestException.class,
          () -> orderSignService.handleRequest(request));

      assertTrue(exception.getMessage().contains("Mismatched hook"));
    }
  }

  @Nested
  @DisplayName("Resource Validation")
  class ResourceValidation {

    @Test
    @DisplayName("Should throw 400 when draftOrders context is empty")
    void testEmptyDraftOrders_Returns400() {
      ResolvedResources context = new ResolvedResources();
      context.setPatient(CdsHooksTestUtils.createTestPatient("test-patient"));
      context.setCoverage(CdsHooksTestUtils.createTestCoverage("test-coverage", "org1234"));
      context.setOrders(Collections.emptyList()); // Empty orders

      CdsHooksException.BadRequestException exception = assertThrows(
          CdsHooksException.BadRequestException.class,
          () -> orderSignService.validateExtractedResources(context));

      assertTrue(exception.getMessage().contains("draftOrders"));
    }

    @Test
    @DisplayName("Should pass validation when draftOrders has orders")
    void testValidDraftOrders_PassesValidation() {
      ResolvedResources context = new ResolvedResources();
      context.setPatient(CdsHooksTestUtils.createTestPatient("test-patient"));
      context.setCoverage(CdsHooksTestUtils.createTestCoverage("test-coverage", "org1234"));
      context.setOrders(List.of(
          CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0250", "test-patient")));

      // Should not throw
      assertDoesNotThrow(() -> orderSignService.validateExtractedResources(context));
    }
  }

  @Nested
  @DisplayName("Resource Selection - order-sign Behavior")
  class ResourceSelection {

    @Test
    @DisplayName("Should process ALL orders (no selections filtering)")
    void testSelectsAllOrders() {
      ResolvedResources context = new ResolvedResources();
      DeviceRequest dr1 = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0250", "patient1");
      DeviceRequest dr2 = CdsHooksTestUtils.createTestDeviceRequest("dr-2", "E0251", "patient1");
      MedicationRequest mr1 = CdsHooksTestUtils.createTestMedicationRequest("mr-1", "1049502", "patient1");

      context.setOrders(List.of(dr1, dr2, mr1));

      List<Resource> selected = orderSignService.selectContextResources(context);

      assertEquals(3, selected.size(), "order-sign should process ALL draft orders");
      assertTrue(selected.contains(dr1));
      assertTrue(selected.contains(dr2));
      assertTrue(selected.contains(mr1));
    }
  }
}
