package org.hl7.davinci.common;

import org.hl7.davinci.cdshooks.CdsHooksTestUtils;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FhirCodeExtractorTest {

  @Nested
  @DisplayName("SupplyRequest code extraction")
  class SupplyRequestTests {

    @Test
    @DisplayName("Extracts codes from SupplyRequest with itemCodeableConcept")
    void extractsCodes_fromSupplyRequest() {
      SupplyRequest request = CdsHooksTestUtils.createTestSupplyRequest("sr-1", "E0100", "patient-1");

      List<Coding> codes = FhirCodeExtractor.extractCodes(request, false);

      assertFalse(codes.isEmpty(), "Should extract codes from SupplyRequest");
      assertEquals("E0100", codes.get(0).getCode());
    }

    @Test
    @DisplayName("Returns empty list from SupplyRequest without itemCodeableConcept")
    void returnsEmpty_fromSupplyRequestWithoutItem() {
      SupplyRequest request = new SupplyRequest();
      request.setId("sr-empty");

      List<Coding> codes = FhirCodeExtractor.extractCodes(request, false);

      assertTrue(codes.isEmpty());
    }

    @Test
    @DisplayName("Extracts codes from SupplyRequest with itemReference via resolved Medication")
    void extractsCodes_fromSupplyRequestWithResolvedMedication() {
      SupplyRequest request = new SupplyRequest();
      request.setId("sr-ref");
      request.setItem(new Reference("Medication/med-1"));

      Medication medication = new Medication();
      medication.setCode(new CodeableConcept().addCoding(new Coding()
          .setSystem("http://www.nlm.nih.gov/research/umls/rxnorm")
          .setCode("197361")));

      List<Coding> codes = FhirCodeExtractor.extractCodes(request, false, medication);

      assertFalse(codes.isEmpty(), "Should extract codes from resolved Medication");
      assertEquals("197361", codes.get(0).getCode());
    }

    @Test
    @DisplayName("Returns empty list from SupplyRequest with itemReference but no resolved resource")
    void returnsEmpty_fromSupplyRequestWithUnresolvedReference() {
      SupplyRequest request = new SupplyRequest();
      request.setId("sr-unresolved");
      request.setItem(new Reference("Medication/med-1"));

      List<Coding> codes = FhirCodeExtractor.extractCodes(request, false, null);

      assertTrue(codes.isEmpty());
    }
  }

  @Nested
  @DisplayName("Two-parameter overload")
  class TwoParamOverloadTests {

    @Test
    @DisplayName("Two-param overload works for DeviceRequest")
    void twoParamOverload_worksForDeviceRequest() {
      DeviceRequest request = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient-1");

      List<Coding> codes = FhirCodeExtractor.extractCodes(request, false);

      assertFalse(codes.isEmpty());
      assertEquals("E0424", codes.get(0).getCode());
    }

    @Test
    @DisplayName("Two-param overload does not throw for MedicationRequest medicationReference")
    void twoParamOverload_medicationReferenceDoesNotThrow() {
      MedicationRequest request = new MedicationRequest();
      request.setId("mr-ref-1");
      request.setStatus(MedicationRequest.MedicationRequestStatus.DRAFT);
      request.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
      request.setMedication(new Reference("Medication/med-1"));

      List<Coding> codes = assertDoesNotThrow(() -> FhirCodeExtractor.extractCodes(request, false));

      assertNotNull(codes);
      assertTrue(codes.isEmpty(), "No resolved Medication means references cannot be resolved");
    }
  }

  @Nested
  @DisplayName("Existing resource type regression")
  class ExistingResourceTypeTests {

    @Test
    @DisplayName("ServiceRequest extracts codes correctly")
    void serviceRequest_extractsCodes() {
      ServiceRequest request = CdsHooksTestUtils.createTestServiceRequest("sr-1", "99213", "patient-1");

      List<Coding> codes = FhirCodeExtractor.extractCodes(request, false, null);

      assertFalse(codes.isEmpty());
      assertEquals("99213", codes.get(0).getCode());
    }

    @Test
    @DisplayName("MedicationRequest extracts codes from medicationCodeableConcept")
    void medicationRequest_extractsCodes() {
      MedicationRequest request = CdsHooksTestUtils.createTestMedicationRequest("mr-1", "197361", "patient-1");

      List<Coding> codes = FhirCodeExtractor.extractCodes(request, false, null);

      assertFalse(codes.isEmpty());
      assertEquals("197361", codes.get(0).getCode());
    }

    @Test
    @DisplayName("Encounter extracts class codes")
    void encounter_extractsCodes() {
      Encounter encounter = CdsHooksTestUtils.createTestEncounter("enc-1", "IMP", "patient-1");

      List<Coding> codes = FhirCodeExtractor.extractCodes(encounter, false, null);

      assertFalse(codes.isEmpty());
      assertEquals("IMP", codes.get(0).getCode());
    }

    @Test
    @DisplayName("Appointment extracts service type codes")
    void appointment_extractsCodes() {
      Appointment appointment = CdsHooksTestUtils.createTestAppointment("apt-1", "394579002", "patient-1");

      List<Coding> codes = FhirCodeExtractor.extractCodes(appointment, false, null);

      assertFalse(codes.isEmpty());
      assertEquals("394579002", codes.get(0).getCode());
    }
  }

  @Nested
  @DisplayName("System normalization")
  class NormalizationTests {

    @Test
    @DisplayName("Normalizes https:// to http:// when enabled")
    void normalizesSystem() {
      DeviceRequest request = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient-1");

      List<Coding> codes = FhirCodeExtractor.extractCodes(request, true);

      assertFalse(codes.isEmpty());
      assertEquals("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", codes.get(0).getSystem());
    }
  }

  @Nested
  @DisplayName("hasMatchingCode")
  class HasMatchingCodeTests {

    private final DeviceRequest request =
        CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient-1");

    @Test
    @DisplayName("Matches on system and code, normalizing https to http on either side")
    void matchesSystemAndCode_withNormalization() {
      assertTrue(FhirCodeExtractor.hasMatchingCode(request,
          new Coding("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", "E0424", null)));
      assertTrue(FhirCodeExtractor.hasMatchingCode(request,
          new Coding("https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", "E0424", null)));
    }

    @Test
    @DisplayName("Rejects same code under a different system")
    void rejectsDifferentSystem() {
      assertFalse(FhirCodeExtractor.hasMatchingCode(request,
          new Coding("http://other.example.org", "E0424", null)));
    }

    @Test
    @DisplayName("Rejects different code and null/empty targets")
    void rejectsDifferentCodeAndNullTarget() {
      assertFalse(FhirCodeExtractor.hasMatchingCode(request,
          new Coding("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", "E9999", null)));
      assertFalse(FhirCodeExtractor.hasMatchingCode(request, null));
      assertFalse(FhirCodeExtractor.hasMatchingCode(request, new Coding()));
    }
  }
}
