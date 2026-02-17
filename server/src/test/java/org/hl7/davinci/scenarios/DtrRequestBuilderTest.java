package org.hl7.davinci.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.hl7.davinci.scenarios.DtrRequestBuilder.DtrScenario;
import org.hl7.davinci.scenarios.DtrRequestBuilder.DtrVariant;
import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.fhir.r4.model.Coding;
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
        List.of("http://hl7.org/fhir/us/davinci-dtr/Questionnaire/HomeOxygenTherapy"),
        false);

    DtrScenario scenario = DtrRequestBuilder.build(List.of(metadata)).get(0);
    assertEquals(1, scenario.variants().size());
    assertEquals("Canonical", scenario.variants().get(0).label());
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
            "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/ImmunosuppressiveDrugs",
            "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/ImmunosuppressiveDrugsProgressNote"),
        false);

    DtrScenario scenario = DtrRequestBuilder.build(List.of(metadata)).get(0);

    Set<String> canonicalLabels = labelsForPathType(scenario.variants(), "canonical");
    assertEquals(2, canonicalLabels.size());
    assertTrue(canonicalLabels.contains("Canonical (ImmunosuppressiveDrugs)"));
    assertTrue(canonicalLabels.contains("Canonical (ImmunosuppressiveDrugsProgressNote)"));

    Set<String> combinedLabels = labelsForPathType(scenario.variants(), "combined");
    assertEquals(2, combinedLabels.size());
    assertTrue(combinedLabels.contains("Combined (ImmunosuppressiveDrugs)"));
    assertTrue(combinedLabels.contains("Combined (ImmunosuppressiveDrugsProgressNote)"));

    Set<String> orderLabels = labelsForPathType(scenario.variants(), "order");
    assertEquals(Set.of("Order"), orderLabels);
  }

  @Test
  @DisplayName("Questionnaire label extraction strips canonical version")
  void questionnaireNameStripsVersion() {
    String name = DtrRequestBuilder.questionnaireNameFromUrl(
        "http://hl7.org/fhir/us/davinci-dtr/Questionnaire/HomeOxygenTherapy|1.0.0");

    assertEquals("HomeOxygenTherapy", name);
  }

  private Set<String> labelsForPathType(List<DtrVariant> variants, String pathType) {
    return variants.stream()
        .filter(v -> pathType.equals(v.pathType()))
        .map(DtrVariant::label)
        .collect(Collectors.toSet());
  }
}
