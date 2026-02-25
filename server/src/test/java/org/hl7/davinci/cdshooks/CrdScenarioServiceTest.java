package org.hl7.davinci.cdshooks;

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

import ca.uhn.fhir.context.FhirContext;

class CrdScenarioServiceTest {

  private ScenarioMetadataProvider metadataProvider;
  private CrdScenarioService service;

  @BeforeEach
  void setUp() {
    metadataProvider = mock(ScenarioMetadataProvider.class);
    service = new CrdScenarioService(metadataProvider, FhirContext.forR4Cached());
  }

  @Test
  void getScenarios_buildsDtosFromMetadata() {
    when(metadataProvider.getMetadata()).thenReturn(List.of(metadata("order-select", "DeviceRequest")));

    List<CrdScenarioService.CrdScenarioDto> scenarios = service.getScenarios();

    assertEquals(1, scenarios.size());
    assertEquals("oxygen", scenarios.get(0).id());
    assertEquals(1, scenarios.get(0).hooks().size());
    assertEquals("order-select", scenarios.get(0).hooks().get(0).hookName());
  }

  @Test
  void findScenario_returnsEmptyWhenNotFound() {
    when(metadataProvider.getMetadata()).thenReturn(List.of(metadata("order-sign", "DeviceRequest")));

    assertTrue(service.findScenario("does-not-exist").isEmpty());
  }

  @Test
  void findHookRequestJson_returnsJsonForMatchingHook() {
    when(metadataProvider.getMetadata()).thenReturn(List.of(metadata("order-sign", "DeviceRequest")));

    var json = service.findHookRequestJson("oxygen", "order-sign");
    assertTrue(json.isPresent());
    assertTrue(json.get().contains("\"hook\" : \"order-sign\""));
    assertTrue(service.findHookRequestJson("oxygen", "appointment-book").isEmpty());
  }

  private ScenarioMetadata metadata(String hook, String orderType) {
    return new ScenarioMetadata(
        "oxygen",
        "Home Oxygen",
        null,
        List.of(new Coding("http://example.org", "E0424", "Stationary Oxygen")),
        List.of(hook),
        orderType,
        List.of(),
        false,
        false,
        false);
  }
}
