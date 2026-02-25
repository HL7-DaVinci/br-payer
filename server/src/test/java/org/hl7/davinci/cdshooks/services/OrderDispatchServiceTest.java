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

@ExtendWith(MockitoExtension.class)
class OrderDispatchServiceTest {

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
  private OrderDispatchService orderDispatchService;

  @BeforeEach
  void setUp() {
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
          "order-sign", "hospital-beds-and-accessories-order-sign.json");

      CdsHooksException.BadRequestException exception = assertThrows(
          CdsHooksException.BadRequestException.class,
          () -> orderDispatchService.handleRequest(request));

      assertTrue(exception.getMessage().contains("Mismatched hook"));
    }
  }

  @Nested
  @DisplayName("Resource Validation")
  class ResourceValidation {

    @Test
    @DisplayName("Should throw 400 when dispatchedOrders context is empty")
    void testEmptyDispatchedOrders_Returns400() {
      ResolvedResources context = new ResolvedResources();
      context.setPatient(CdsHooksTestUtils.createTestPatient("test-patient"));
      context.setCoverage(CdsHooksTestUtils.createTestCoverage("test-coverage", "org1234"));
      context.setOrders(Collections.emptyList());

      CdsHooksException.BadRequestException exception = assertThrows(
          CdsHooksException.BadRequestException.class,
          () -> orderDispatchService.validateExtractedResources(context));

      assertTrue(exception.getMessage().contains("dispatchedOrders"));
    }

    @Test
    @DisplayName("Should pass validation when dispatchedOrders has orders")
    void testValidDispatchedOrders_PassesValidation() {
      ResolvedResources context = new ResolvedResources();
      context.setPatient(CdsHooksTestUtils.createTestPatient("test-patient"));
      context.setCoverage(CdsHooksTestUtils.createTestCoverage("test-coverage", "org1234"));
      context.setPractitioners(List.of(
          CdsHooksTestUtils.createTestPractitioner("practitioner-1")));
      context.setOrders(List.of(
          CdsHooksTestUtils.createTestServiceRequest("sr-1", "70553", "test-patient")));

      assertDoesNotThrow(() -> orderDispatchService.validateExtractedResources(context));
    }

    @Test
    @DisplayName("Should throw 400 when performer context is missing")
    void testMissingPerformerContext_Returns400() {
      ResolvedResources context = new ResolvedResources();
      context.setPatient(CdsHooksTestUtils.createTestPatient("test-patient"));
      context.setCoverage(CdsHooksTestUtils.createTestCoverage("test-coverage", "org1234"));
      context.setOrders(List.of(
          CdsHooksTestUtils.createTestServiceRequest("sr-1", "70553", "test-patient")));

      CdsHooksException.BadRequestException exception = assertThrows(
          CdsHooksException.BadRequestException.class,
          () -> orderDispatchService.validateExtractedResources(context));

      assertTrue(exception.getMessage().contains("performer"));
    }

    @Test
    @DisplayName("Should throw 400 when fulfillmentTask has no focus reference")
    void testFulfillmentTaskWithoutFocus_Returns400() {
      ResolvedResources context = validDispatchContext();
      Task task = new Task();
      task.setId("task-1");
      context.setTasks(List.of(task));

      CdsHooksException.BadRequestException exception = assertThrows(
          CdsHooksException.BadRequestException.class,
          () -> orderDispatchService.validateExtractedResources(context));

      assertTrue(exception.getMessage().contains("fulfillmentTasks"));
    }

    @Test
    @DisplayName("Should throw 400 when fulfillmentTask references non-dispatched order")
    void testFulfillmentTaskMismatchedFocus_Returns400() {
      ResolvedResources context = validDispatchContext();
      Task task = new Task();
      task.setId("task-1");
      task.setFocus(new Reference("ServiceRequest/sr-99"));
      context.setTasks(List.of(task));

      CdsHooksException.BadRequestException exception = assertThrows(
          CdsHooksException.BadRequestException.class,
          () -> orderDispatchService.validateExtractedResources(context));

      assertTrue(exception.getMessage().contains("fulfillmentTasks"));
    }

    @Test
    @DisplayName("Should pass validation when fulfillmentTask references dispatched order")
    void testFulfillmentTaskMatchingFocus_PassesValidation() {
      ResolvedResources context = validDispatchContext();
      Task task = new Task();
      task.setId("task-1");
      task.setFocus(new Reference("ServiceRequest/sr-1"));
      context.setTasks(List.of(task));

      assertDoesNotThrow(() -> orderDispatchService.validateExtractedResources(context));
    }
  }

  @Nested
  @DisplayName("Resource Selection - order-dispatch Behavior")
  class ResourceSelection {

    @Test
    @DisplayName("Should process ALL dispatched orders")
    void testSelectsAllOrders() {
      ResolvedResources context = new ResolvedResources();
      ServiceRequest sr1 = CdsHooksTestUtils.createTestServiceRequest("sr-1", "70553", "patient1");
      DeviceRequest dr1 = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient1");

      context.setOrders(List.of(sr1, dr1));

      List<Resource> selected = orderDispatchService.selectContextResources(context);

      assertEquals(2, selected.size(), "order-dispatch should process ALL dispatched orders");
      assertTrue(selected.contains(sr1));
      assertTrue(selected.contains(dr1));
    }
  }

  private ResolvedResources validDispatchContext() {
    ResolvedResources context = new ResolvedResources();
    context.setPatient(CdsHooksTestUtils.createTestPatient("test-patient"));
    context.setCoverage(CdsHooksTestUtils.createTestCoverage("test-coverage", "org1234"));
    context.setPractitioners(List.of(CdsHooksTestUtils.createTestPractitioner("practitioner-1")));
    context.setOrders(List.of(CdsHooksTestUtils.createTestServiceRequest("sr-1", "70553", "test-patient")));
    return context;
  }
}
