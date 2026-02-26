package org.hl7.davinci.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.hl7.davinci.dtr.DtrConstants;
import org.hl7.davinci.scenarios.DtrRequestBuilder.DtrScenario;
import org.hl7.davinci.scenarios.DtrRequestBuilder.DtrVariant;
import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DtrRequestBuilderTest {

  @Test
  @DisplayName("Single-questionnaire canonical variant keeps base label")
  void singleQuestionnaireCanonicalLabel() {
    ScenarioMetadata metadata = new ScenarioMetadata(
        "single",
        "Single Questionnaire",
        null,
        List.of(),
        List.of(),
        null,
        List.of("http://example.org/fhir/Questionnaire/HomeOxygenTherapy"),
        false, false, false);

    DtrScenario scenario = DtrRequestBuilder.build(List.of(metadata)).get(0);
    assertEquals(1, scenario.variants().size());
    assertEquals("Questionnaire", scenario.variants().get(0).label());
  }

  @Test
  @DisplayName("Multi-questionnaire canonical and combined variants include questionnaire names")
  void multiQuestionnaireVariantLabelsAreDistinct() {
    Coding focus = new Coding()
        .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
        .setCode("E0424")
        .setDisplay("Home oxygen");

    ScenarioMetadata metadata = new ScenarioMetadata(
        "immuno",
        "Immunosuppressive Drugs",
        null,
        List.of(focus),
        List.of("order-select"),
        "DeviceRequest",
        List.of(
            "http://example.org/fhir/Questionnaire/ImmunosuppressiveDrugs",
            "http://example.org/fhir/Questionnaire/ImmunosuppressiveDrugsProgressNote"),
        false, false, false);

    DtrScenario scenario = DtrRequestBuilder.build(List.of(metadata)).get(0);

    Set<String> canonicalLabels = labelsForPathType(scenario.variants(), "canonical");
    assertEquals(2, canonicalLabels.size());
    assertTrue(canonicalLabels.contains("Questionnaire (ImmunosuppressiveDrugs)"));
    assertTrue(canonicalLabels.contains("Questionnaire (ImmunosuppressiveDrugsProgressNote)"));

    Set<String> combinedLabels = labelsForPathType(scenario.variants(), "combined");
    assertEquals(2, combinedLabels.size());
    assertTrue(combinedLabels.contains("Questionnaire & Order (ImmunosuppressiveDrugs)"));
    assertTrue(combinedLabels.contains("Questionnaire & Order (ImmunosuppressiveDrugsProgressNote)"));

    Set<String> orderLabels = labelsForPathType(scenario.variants(), "order");
    assertEquals(Set.of("Order"), orderLabels);
  }

  @Test
  @DisplayName("Questionnaire label extraction strips canonical version")
  void questionnaireNameStripsVersion() {
    String name = DtrRequestBuilder.questionnaireNameFromUrl(
        "http://example.org/fhir/Questionnaire/HomeOxygenTherapy|1.0.0");

    assertEquals("HomeOxygenTherapy", name);
  }

  @Test
  @DisplayName("DeviceRequest order variant includes authoredOn/requester and omits display")
  void deviceRequestOrderVariantHasRequiredFields() {
    ScenarioMetadata metadata = new ScenarioMetadata(
        "device",
        "Device Order",
        null,
        List.of(new Coding()
            .setSystem("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets")
            .setCode("E0424")
            .setDisplay("Stationary Oxygen")),
        List.of("order-select"),
        "DeviceRequest",
        List.of(),
        false, false, false);

    DtrScenario scenario = DtrRequestBuilder.build(List.of(metadata)).get(0);
    Resource order = extractOrderResource(orderVariant(scenario));
    assertTrue(order instanceof DeviceRequest);
    DeviceRequest dr = (DeviceRequest) order;
    assertNotNull(dr.getAuthoredOn());
    assertNotNull(dr.getRequester());
  }

  @Test
  @DisplayName("MedicationRequest order variant includes authoredOn/requester")
  void medicationRequestOrderVariantHasRequiredFields() {
    ScenarioMetadata metadata = new ScenarioMetadata(
        "med",
        "Medication Order",
        null,
        List.of(new Coding()
            .setSystem("http://www.nlm.nih.gov/research/umls/rxnorm")
            .setCode("197696")
            .setDisplay("Hydrocodone 5 MG / Acetaminophen 325 MG")),
        List.of("order-select"),
        "MedicationRequest",
        List.of(),
        false, false, false);

    DtrScenario scenario = DtrRequestBuilder.build(List.of(metadata)).get(0);
    Resource order = extractOrderResource(orderVariant(scenario));
    assertTrue(order instanceof MedicationRequest);
    MedicationRequest mr = (MedicationRequest) order;
    assertNotNull(mr.getAuthoredOn());
    assertNotNull(mr.getRequester());
    assertFalse(mr.getMedicationCodeableConcept().getCodingFirstRep().hasDisplay());
  }

  @Test
  @DisplayName("Appointment order variant includes patient+performer participants and times")
  void appointmentOrderVariantHasRequiredFields() {
    ScenarioMetadata metadata = new ScenarioMetadata(
        "appt",
        "Appointment Order",
        null,
        List.of(new Coding()
            .setSystem("http://snomed.info/sct")
            .setCode("91251008")
            .setDisplay("Physical therapy")),
        List.of("appointment-book"),
        "Appointment",
        List.of(),
        false, false, false);

    DtrScenario scenario = DtrRequestBuilder.build(List.of(metadata)).get(0);
    Resource order = extractOrderResource(orderVariant(scenario));
    assertTrue(order instanceof Appointment);
    Appointment appointment = (Appointment) order;
    assertNotNull(appointment.getStart());
    assertNotNull(appointment.getEnd());
    assertTrue(appointment.getParticipant().size() >= 2);
    assertTrue(appointment.getParticipant().stream()
        .anyMatch(p -> p.getActor() != null && "#appointment-patient".equals(p.getActor().getReference())));
    assertTrue(appointment.getParticipant().stream()
        .anyMatch(p -> p.getType().stream().anyMatch(t -> t.getCoding().stream()
            .anyMatch(c -> "http://terminology.hl7.org/CodeSystem/v3-ParticipationType".equals(c.getSystem())
                && "PPRF".equals(c.getCode())))));
  }

  @Test
  @DisplayName("Adaptive questionnaire with initial items produces adaptive search variant")
  void adaptiveWithInitialItems_producesSearchVariant() {
    ScenarioMetadata metadata = new ScenarioMetadata(
        "opioid",
        "Opioid Prescribing",
        null,
        List.of(),
        List.of(),
        null,
        List.of("http://example.org/fhir/Questionnaire/OpioidJustification"),
        true, false, true);

    DtrScenario scenario = DtrRequestBuilder.build(List.of(metadata)).get(0);
    Set<String> canonicalLabels = labelsForPathType(scenario.variants(), "canonical");
    assertEquals(2, canonicalLabels.size());
    assertTrue(canonicalLabels.contains("Questionnaire"));
    assertTrue(canonicalLabels.contains("Questionnaire (search)"));

    DtrVariant searchVariant = scenario.variants().stream()
        .filter(v -> v.id().endsWith("-canonical-search"))
        .findFirst().orElseThrow();
    assertEquals("search", searchVariant.headers().get(DtrConstants.ADAPTIVE_MODE_HEADER));
  }

  @Test
  @DisplayName("Adaptive search questionnaire with initial items produces initial variant")
  void adaptiveSearch_withInitialItems_producesInitialVariant() {
    ScenarioMetadata metadata = new ScenarioMetadata(
        "home-health",
        "Home Health Assessment",
        null,
        List.of(),
        List.of(),
        null,
        List.of("http://example.org/fhir/Questionnaire/HomeHealthAssessment"),
        true, true, true);

    DtrScenario scenario = DtrRequestBuilder.build(List.of(metadata)).get(0);
    Set<String> canonicalLabels = labelsForPathType(scenario.variants(), "canonical");
    assertEquals(2, canonicalLabels.size());
    assertTrue(canonicalLabels.contains("Questionnaire"));
    assertTrue(canonicalLabels.contains("Questionnaire (initial)"));

    DtrVariant initialVariant = scenario.variants().stream()
        .filter(v -> v.id().endsWith("-canonical-initial"))
        .findFirst().orElseThrow();
    assertEquals("initial", initialVariant.headers().get(DtrConstants.ADAPTIVE_MODE_HEADER));
  }

  @Test
  @DisplayName("Adaptive search questionnaire without initial items produces single variant")
  void adaptiveSearch_noInitialItems_singleVariant() {
    ScenarioMetadata metadata = new ScenarioMetadata(
        "all-conditional",
        "All Conditional",
        null,
        List.of(),
        List.of(),
        null,
        List.of("http://example.org/fhir/Questionnaire/AllConditional"),
        true, true, false);

    DtrScenario scenario = DtrRequestBuilder.build(List.of(metadata)).get(0);
    Set<String> canonicalLabels = labelsForPathType(scenario.variants(), "canonical");
    assertEquals(1, canonicalLabels.size());
    assertEquals("Questionnaire", canonicalLabels.iterator().next());
  }

  private Set<String> labelsForPathType(List<DtrVariant> variants, String pathType) {
    return variants.stream()
        .filter(v -> pathType.equals(v.pathType()))
        .map(DtrVariant::label)
        .collect(Collectors.toSet());
  }

  private DtrVariant orderVariant(DtrScenario scenario) {
    return scenario.variants().stream()
        .filter(v -> "order".equals(v.pathType()))
        .findFirst()
        .orElseThrow();
  }

  private Resource extractOrderResource(DtrVariant variant) {
    Parameters parameters = variant.parameters();
    return parameters.getParameter().stream()
        .filter(p -> "order".equals(p.getName()))
        .map(Parameters.ParametersParameterComponent::getResource)
        .findFirst()
        .orElseThrow();
  }
}
