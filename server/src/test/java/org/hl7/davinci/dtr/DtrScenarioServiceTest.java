package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.davinci.scenarios.ScenarioMetadataProvider;
import org.hl7.fhir.r4.model.Coding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import ca.uhn.fhir.context.FhirContext;

class DtrScenarioServiceTest {

  private ScenarioMetadataProvider metadataProvider;
  private DtrScenarioService service;

  @BeforeEach
  void setUp() {
    metadataProvider = mock(ScenarioMetadataProvider.class);
    service = new DtrScenarioService(metadataProvider, FhirContext.forR4Cached());
  }

  @Test
  void getScenarios_buildsVariantsFromMetadata() {
    when(metadataProvider.getMetadata()).thenReturn(List.of(metadata()));

    var scenarios = service.getScenarios();
    assertEquals(1, scenarios.size());
    assertEquals("oxygen", scenarios.get(0).id());
    assertTrue(scenarios.get(0).variants().stream().anyMatch(v -> "canonical".equals(v.pathType())));
  }

  @Test
  void findVariantParameters_supportsShortAndFullVariantIds() {
    when(metadataProvider.getMetadata()).thenReturn(List.of(metadata()));

    assertTrue(service.findVariantParameters("oxygen", "home-oxygen-therapy-canonical").isPresent());
    assertTrue(service.findVariantParameters("oxygen", "oxygen-home-oxygen-therapy-canonical").isPresent());
    assertTrue(service.findVariantParameters("oxygen", "order").isPresent());
    assertTrue(service.findVariantParameters("oxygen", "oxygen-order").isPresent());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = "missing")
  void findVariantParameters_returnsEmptyForInvalidVariantIds(String variantId) {
    when(metadataProvider.getMetadata()).thenReturn(List.of(metadata()));

    assertTrue(service.findVariantParameters("oxygen", variantId).isEmpty());
  }

  private ScenarioMetadata metadata() {
    return new ScenarioMetadata(
        "oxygen",
        "Home Oxygen",
        null,
        List.of(new Coding("http://example.org", "E0424", "Stationary Oxygen")),
        List.of("order-select"),
        "DeviceRequest",
        List.of("http://hl7.org/fhir/us/davinci-dtr/Questionnaire/HomeOxygenTherapy"),
        false,
        false,
        false);
  }
}
