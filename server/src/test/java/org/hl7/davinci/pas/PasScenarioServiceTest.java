package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        false, false, false);
  }
}
