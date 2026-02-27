package org.hl7.davinci.scenarios.pas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.davinci.scenarios.ScenarioMetadataProvider;
import org.hl7.fhir.r4.model.Coding;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.context.FhirContext;

class PasScenarioServiceTest {

  @Test
  void getScenarios_rebuildsUsingLatestRepositoryMetadata() {
    ScenarioMetadata first = scenario("first", "First Scenario");
    ScenarioMetadata second = scenario("second", "Second Scenario");
    AtomicInteger callCount = new AtomicInteger(0);

    ScenarioMetadataProvider metadataProvider = new ScenarioMetadataProvider(null) {
      @Override
      public List<ScenarioMetadata> getMetadata() {
        return callCount.getAndIncrement() == 0 ? List.of(first) : List.of(second);
      }
    };
    PasScenarioService service = new PasScenarioService(metadataProvider, FhirContext.forR4Cached());

    List<PasScenarioService.PasScenarioDto> initial = service.getScenarios();
    List<PasScenarioService.PasScenarioDto> refreshed = service.getScenarios();

    assertEquals(List.of("first"), initial.stream().map(PasScenarioService.PasScenarioDto::id).toList());
    assertEquals(List.of("second"), refreshed.stream().map(PasScenarioService.PasScenarioDto::id).toList());
    assertEquals(2, callCount.get());
  }

  @Test
  void findScenarioAndVariants_supportMissingAndShortVariantIds() {
    ScenarioMetadataProvider metadataProvider = new ScenarioMetadataProvider(null) {
      @Override
      public List<ScenarioMetadata> getMetadata() {
        return List.of(scenario("oxygen", "Home Oxygen"));
      }
    };
    PasScenarioService service = new PasScenarioService(metadataProvider, FhirContext.forR4Cached());

    assertTrue(service.findScenario("oxygen").isPresent());
    assertTrue(service.findScenario("missing").isEmpty());
    assertTrue(service.findVariantBundle("oxygen", "initial").isPresent());
    assertTrue(service.findVariantBundle("oxygen", "oxygen-initial").isPresent());
    assertTrue(service.findVariantBundle("oxygen", "does-not-exist").isEmpty());
  }

  @Test
  void findVariantBundle_returnsEmptyForNullOrBlankVariantId() {
    ScenarioMetadataProvider metadataProvider = new ScenarioMetadataProvider(null) {
      @Override
      public List<ScenarioMetadata> getMetadata() {
        return List.of(scenario("oxygen", "Home Oxygen"));
      }
    };
    PasScenarioService service = new PasScenarioService(metadataProvider, FhirContext.forR4Cached());

    assertTrue(service.findVariantBundle("oxygen", null).isEmpty());
    assertTrue(service.findVariantBundle("oxygen", "").isEmpty());
    assertFalse(service.findVariantBundle("oxygen", "initial").isEmpty());
  }

  private ScenarioMetadata scenario(String id, String name) {
    Coding focus = new Coding(
        "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", "E0424", "Stationary Oxygen");
    return new ScenarioMetadata(
        id,
        name,
        null,
        List.of(focus),
        List.of("order-select"),
        "ServiceRequest",
        List.of(),
        false,
        false,
        false);
  }
}
