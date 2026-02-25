package org.hl7.davinci.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.junit.jupiter.api.Test;

class ScenarioResourceUtilTest {

  @Test
  void buildOrderResource_generatesExpectedResourceTypesAndFields() {
    Coding code = new Coding("http://example.org", "E0424", "Stationary Oxygen");

    Resource device = ScenarioResourceUtil.buildOrderResource(code, "DeviceRequest", "scenario");
    Resource medication = ScenarioResourceUtil.buildOrderResource(code, "MedicationRequest", "scenario");
    Resource service = ScenarioResourceUtil.buildOrderResource(code, "ServiceRequest", "scenario");
    Resource appointment = ScenarioResourceUtil.buildOrderResource(code, "Appointment", "scenario");

    assertTrue(device instanceof DeviceRequest);
    assertTrue(medication instanceof MedicationRequest);
    assertTrue(service instanceof ServiceRequest);
    assertTrue(appointment instanceof Appointment);
    assertNull(((DeviceRequest) device).getCodeCodeableConcept().getCodingFirstRep().getDisplay());
    assertNull(((MedicationRequest) medication).getMedicationCodeableConcept().getCodingFirstRep().getDisplay());
    assertNull(((ServiceRequest) service).getCode().getCodingFirstRep().getDisplay());
  }

  @Test
  void buildOrderResource_appointmentIncludesRequiredParticipantsAndTimes() {
    Coding code = new Coding("http://snomed.info/sct", "91251008", "Physical therapy");
    Appointment appointment = (Appointment) ScenarioResourceUtil.buildOrderResource(code, "Appointment", "scenario");

    assertNotNull(appointment.getStart());
    assertNotNull(appointment.getEnd());
    assertFalse(appointment.getContained().isEmpty());
    assertTrue(appointment.getParticipant().stream()
        .anyMatch(p -> p.hasActor() && "#appointment-patient".equals(p.getActor().getReference())));
  }

  @Test
  void buildOrderResource_returnsNullForUnsupportedType() {
    Coding code = new Coding("http://example.org", "code", "display");
    assertNull(ScenarioResourceUtil.buildOrderResource(code, "DocumentReference", "scenario"));
  }

  @Test
  void buildDescription_usesMetadataDescriptionWhenPresent() {
    ScenarioMetadata metadata = new ScenarioMetadata(
        "id", "name", "Explicit description", List.of(), List.of(), null, List.of(), false, false, false);

    assertEquals("Explicit description", ScenarioResourceUtil.buildDescription(metadata));
  }

  @Test
  void buildDescription_fallsBackToFocusAndOrderTypeOrAdaptiveFlags() {
    ScenarioMetadata orderBased = new ScenarioMetadata(
        "id",
        "name",
        null,
        List.of(new Coding("http://example.org", "E0424", "Stationary Oxygen")),
        List.of("order-sign"),
        "ServiceRequest",
        List.of(),
        false,
        false,
        false);
    ScenarioMetadata adaptive = new ScenarioMetadata(
        "id2",
        "name2",
        null,
        List.of(),
        List.of(),
        null,
        List.of(),
        true,
        false,
        false);

    assertTrue(ScenarioResourceUtil.buildDescription(orderBased).contains("ServiceRequest-based"));
    assertTrue(ScenarioResourceUtil.buildDescription(adaptive).contains("Adaptive questionnaire"));
  }
}
