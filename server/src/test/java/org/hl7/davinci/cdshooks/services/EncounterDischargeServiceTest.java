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
 * Unit tests for EncounterDischargeService.
 *
 * EncounterDischargeService is a SECONDARY hook per CRD spec:
 * - MAY return coverage-information but not required
 * - If coverage-info is returned, it SHALL NOT request clinical/admin documentation
 */
@ExtendWith(MockitoExtension.class)
class EncounterDischargeServiceTest {

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
  private EncounterDischargeService encounterDischargeService;

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
    @DisplayName("Should return hook name 'encounter-discharge'")
    void testGetHookName() {
      assertEquals("encounter-discharge", encounterDischargeService.getHookName());
    }

    @Test
    @DisplayName("Should throw 400 when hook name doesn't match")
    void testWrongHookName_Returns400() throws IOException {
      CdsServiceRequestJson request = CdsHooksTestUtils.loadGeneratedRequest(
          "order-sign", "hospital-beds-and-accessories-order-sign.json");

      CdsHooksException.BadRequestException exception = assertThrows(
          CdsHooksException.BadRequestException.class,
          () -> encounterDischargeService.handleRequest(request));

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
          () -> encounterDischargeService.validateExtractedResources(context));

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
      encounter.setStatus(Encounter.EncounterStatus.FINISHED);
      context.setEncounter(encounter);

      assertDoesNotThrow(() -> encounterDischargeService.validateExtractedResources(context));
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

      List<Resource> selected = encounterDischargeService.selectContextResources(context);

      assertEquals(1, selected.size());
      assertTrue(selected.get(0) instanceof Encounter);
      assertEquals("enc-001", selected.get(0).getIdElement().getIdPart());
    }
  }

  @Nested
  @DisplayName("Secondary Hook Classification")
  class SecondaryHookClassification {

    @Test
    @DisplayName("encounter-discharge is NOT a primary hook")
    void testIsNotPrimaryHook() {
      String hookName = encounterDischargeService.getHookName();
      assertFalse(
          hookName.equals("order-sign") ||
          hookName.equals("order-dispatch") ||
          hookName.equals("appointment-book"),
          "encounter-discharge should NOT be a primary hook");
    }
  }

  @Nested
  @DisplayName("Discharge Disposition Handling")
  class DischargeDispositionHandling {

    @Test
    @DisplayName("Should handle encounter with discharge disposition")
    void testEncounterWithDischargeDisposition() {
      Encounter encounter = new Encounter();
      encounter.setId("test-enc");
      encounter.setStatus(Encounter.EncounterStatus.FINISHED);

      Encounter.EncounterHospitalizationComponent hospitalization = new Encounter.EncounterHospitalizationComponent();
      CodeableConcept disposition = new CodeableConcept();
      disposition.addCoding()
          .setSystem("http://terminology.hl7.org/CodeSystem/discharge-disposition")
          .setCode("snf")
          .setDisplay("Skilled nursing facility");
      hospitalization.setDischargeDisposition(disposition);
      encounter.setHospitalization(hospitalization);

      assertTrue(encounter.hasHospitalization());
      assertTrue(encounter.getHospitalization().hasDischargeDisposition());
      assertEquals("snf",
          encounter.getHospitalization().getDischargeDisposition().getCodingFirstRep().getCode());
    }

    @Test
    @DisplayName("Should handle encounter without hospitalization")
    void testEncounterWithoutHospitalization() {
      Encounter encounter = new Encounter();
      encounter.setId("test-enc");
      encounter.setStatus(Encounter.EncounterStatus.FINISHED);

      assertFalse(encounter.hasHospitalization());
    }
  }

  @Nested
  @DisplayName("Encounter Code Extraction")
  class EncounterCodeExtraction {

    @Test
    @DisplayName("Should handle encounter with reason codes")
    void testEncounterWithReasonCodes() {
      Encounter encounter = new Encounter();
      encounter.setId("test-enc");

      CodeableConcept reasonCode = new CodeableConcept();
      reasonCode.addCoding()
          .setSystem("http://snomed.info/sct")
          .setCode("263225007")
          .setDisplay("Hip fracture");
      encounter.addReasonCode(reasonCode);

      assertFalse(encounter.getReasonCode().isEmpty());
      assertEquals("263225007", encounter.getReasonCode().get(0).getCodingFirstRep().getCode());
    }
  }
}
