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
 * Unit tests for EncounterStartService.
 *
 * EncounterStartService is a SECONDARY hook per CRD spec:
 * - MAY return coverage-information but not required
 * - If coverage-info is returned, it SHALL NOT request clinical/admin documentation
 */
@ExtendWith(MockitoExtension.class)
class EncounterStartServiceTest {

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
  private EncounterStartService encounterStartService;

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
    @DisplayName("Should return hook name 'encounter-start'")
    void testGetHookName() {
      assertEquals("encounter-start", encounterStartService.getHookName());
    }

    @Test
    @DisplayName("Should throw 400 when hook name doesn't match")
    void testWrongHookName_Returns400() throws IOException {
      CdsServiceRequestJson request = CdsHooksTestUtils.loadGeneratedRequest(
          "order-sign", "hospital-beds-and-accessories-order-sign.json");

      CdsHooksException.BadRequestException exception = assertThrows(
          CdsHooksException.BadRequestException.class,
          () -> encounterStartService.handleRequest(request));

      assertTrue(exception.getMessage().contains("Mismatched hook"));
    }
  }

  @Nested
  @DisplayName("Resource Validation - Encounter Required")
  class ResourceValidation {

    @Test
    @DisplayName("Should throw 400 when encounter is null")
    void testNullEncounter_Returns400() {
      ResolvedResources context = new ResolvedResources();
      context.setPatient(CdsHooksTestUtils.createTestPatient("test-patient"));
      context.setCoverage(CdsHooksTestUtils.createTestCoverage("test-coverage", "org1234"));
      context.setEncounter(null);

      CdsHooksException.BadRequestException exception = assertThrows(
          CdsHooksException.BadRequestException.class,
          () -> encounterStartService.validateExtractedResources(context));

      assertTrue(exception.getMessage().contains("encounterId"));
    }

    @Test
    @DisplayName("Should pass validation when encounter exists")
    void testValidEncounter_PassesValidation() {
      ResolvedResources context = new ResolvedResources();
      context.setPatient(CdsHooksTestUtils.createTestPatient("test-patient"));
      context.setCoverage(CdsHooksTestUtils.createTestCoverage("test-coverage", "org1234"));

      Encounter encounter = new Encounter();
      encounter.setId("enc-001");
      encounter.setStatus(Encounter.EncounterStatus.INPROGRESS);
      context.setEncounter(encounter);

      assertDoesNotThrow(() -> encounterStartService.validateExtractedResources(context));
    }
  }

  @Nested
  @DisplayName("Resource Selection")
  class ResourceSelection {

    @Test
    @DisplayName("Should select the encounter as context resource")
    void testSelectsEncounter() {
      ResolvedResources context = new ResolvedResources();

      Encounter encounter = new Encounter();
      encounter.setId("enc-001");
      context.setEncounter(encounter);

      List<Resource> selected = encounterStartService.selectContextResources(context);

      assertEquals(1, selected.size());
      assertTrue(selected.get(0) instanceof Encounter);
      assertEquals("enc-001", selected.get(0).getIdElement().getIdPart());
    }
  }

  @Nested
  @DisplayName("Secondary Hook Classification")
  class SecondaryHookClassification {

    @Test
    @DisplayName("encounter-start is NOT a primary hook")
    void testIsNotPrimaryHook() {
      String hookName = encounterStartService.getHookName();
      assertFalse(
          hookName.equals("order-sign") ||
          hookName.equals("order-dispatch") ||
          hookName.equals("appointment-book"),
          "encounter-start should NOT be a primary hook");
    }
  }

  @Nested
  @DisplayName("Encounter Code Extraction")
  class EncounterCodeExtraction {

    @Test
    @DisplayName("Should handle encounter with class code")
    void testEncounterWithClassCode() {
      Encounter encounter = new Encounter();
      encounter.setId("test-enc");
      encounter.setClass_(new Coding()
          .setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")
          .setCode("IMP")
          .setDisplay("Inpatient encounter"));

      assertNotNull(encounter.getClass_());
      assertEquals("IMP", encounter.getClass_().getCode());
    }

    @Test
    @DisplayName("Should handle encounter with type codes")
    void testEncounterWithTypeCodes() {
      Encounter encounter = new Encounter();
      encounter.setId("test-enc");

      CodeableConcept type = new CodeableConcept();
      type.addCoding()
          .setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")
          .setCode("IMP")
          .setDisplay("Inpatient");
      encounter.addType(type);

      assertFalse(encounter.getType().isEmpty());
      assertEquals("IMP", encounter.getType().get(0).getCodingFirstRep().getCode());
    }
  }
}
