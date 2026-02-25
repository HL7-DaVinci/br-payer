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
 * Unit tests for AppointmentBookService.
 * 
 * AppointmentBookService is a PRIMARY hook per CRD spec:
 * - SHALL return coverage-information system action for all invocations
 * - Processes appointments from context
 * - Uses serviceType and reasonCode for PlanDefinition matching
 */
@ExtendWith(MockitoExtension.class)
class AppointmentBookServiceTest {

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
  private AppointmentBookService appointmentBookService;

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
          "order-sign", "hospital-beds-and-accessories-order-sign.json");

      CdsHooksException.BadRequestException exception = assertThrows(
          CdsHooksException.BadRequestException.class,
          () -> appointmentBookService.handleRequest(request));

      assertTrue(exception.getMessage().contains("Mismatched hook"));
    }
  }

  @Nested
  @DisplayName("Resource Validation - Appointments Required")
  class ResourceValidation {

    @Test
    @DisplayName("Should throw 400 when appointments context is empty")
    void testEmptyAppointments_Returns400() {
      ResolvedResources context = new ResolvedResources();
      context.setPatient(CdsHooksTestUtils.createTestPatient("test-patient"));
      context.setCoverage(CdsHooksTestUtils.createTestCoverage("test-coverage", "org1234"));
      context.setAppointments(Collections.emptyList());

      CdsHooksException.BadRequestException exception = assertThrows(
          CdsHooksException.BadRequestException.class,
          () -> appointmentBookService.validateExtractedResources(context));

      assertTrue(exception.getMessage().contains("appointments"));
    }

    @Test
    @DisplayName("Should pass validation when appointments exist")
    void testValidAppointments_PassesValidation() {
      ResolvedResources context = new ResolvedResources();
      context.setPatient(CdsHooksTestUtils.createTestPatient("test-patient"));
      context.setCoverage(CdsHooksTestUtils.createTestCoverage("test-coverage", "org1234"));
      context.setAppointments(List.of(
          CdsHooksTestUtils.createTestAppointment("appt-1", "394579002", "test-patient"))); // Cardiology

      assertDoesNotThrow(() -> appointmentBookService.validateExtractedResources(context));
    }
  }

  @Nested
  @DisplayName("Resource Selection - All Appointments")
  class ResourceSelection {

    @Test
    @DisplayName("Should process ALL appointments in context")
    void testSelectsAllAppointments() {
      ResolvedResources context = new ResolvedResources();
      Appointment appt1 = CdsHooksTestUtils.createTestAppointment("appt-1", "394579002", "patient1"); // Cardiology
      Appointment appt2 = CdsHooksTestUtils.createTestAppointment("appt-2", "91251008", "patient1"); // Physical therapy

      context.setAppointments(List.of(appt1, appt2));

      List<Resource> selected = appointmentBookService.selectContextResources(context);

      assertEquals(2, selected.size(), "appointment-book should process ALL appointments");
      assertTrue(selected.contains(appt1));
      assertTrue(selected.contains(appt2));
    }
  }
}
