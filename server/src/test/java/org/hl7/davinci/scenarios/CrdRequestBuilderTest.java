package org.hl7.davinci.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.hl7.davinci.scenarios.CrdRequestBuilder.CrdHookVariant;
import org.hl7.davinci.scenarios.CrdRequestBuilder.CrdScenario;
import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.fhir.r4.model.Coding;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ca.uhn.fhir.context.FhirContext;

class CrdRequestBuilderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void build_skipsMetadataWithoutRequiredTriggerOrFocus() {
    ScenarioMetadata missingFocus = new ScenarioMetadata(
        "missing-focus", "Missing Focus", null, List.of(), List.of("order-sign"),
        "DeviceRequest", List.of(), false, false, false);
    ScenarioMetadata missingHook = new ScenarioMetadata(
        "missing-hook", "Missing Hook", null,
        List.of(new Coding("http://example.org/system", "code", "display")),
        List.of(), "DeviceRequest", List.of(), false, false, false);

    List<CrdScenario> scenarios = CrdRequestBuilder.build(
        FhirContext.forR4Cached(),
        List.of(missingFocus, missingHook));

    assertTrue(scenarios.isEmpty());
  }

  @Test
  void build_orderSelectVariantIncludesSelectionsAndDraftOrders() throws Exception {
    ScenarioMetadata metadata = metadata("order-select", "DeviceRequest");
    List<CrdScenario> scenarios = CrdRequestBuilder.build(FhirContext.forR4Cached(), List.of(metadata));

    assertEquals(1, scenarios.size());
    CrdHookVariant variant = scenarios.get(0).variants().stream()
        .filter(v -> "order-select".equals(v.hookName()))
        .findFirst()
        .orElseThrow();

    JsonNode json = MAPPER.readTree(variant.requestJson());
    JsonNode context = json.get("context");
    assertNotNull(context.get("draftOrders"));
    assertEquals(1, context.get("selections").size());
    assertTrue(context.get("selections").get(0).asText().startsWith("DeviceRequest/"));
  }

  @Test
  void build_appointmentOrderTypeMapsToServiceRequestForOrderSign() throws Exception {
    ScenarioMetadata metadata = metadata("order-sign", "Appointment");
    List<CrdScenario> scenarios = CrdRequestBuilder.build(FhirContext.forR4Cached(), List.of(metadata));

    CrdHookVariant variant = scenarios.get(0).variants().stream()
        .filter(v -> "order-sign".equals(v.hookName()))
        .findFirst()
        .orElseThrow();
    JsonNode json = MAPPER.readTree(variant.requestJson());
    JsonNode orderResource = json.get("context").get("draftOrders").get("entry").get(0).get("resource");

    assertEquals("ServiceRequest", orderResource.get("resourceType").asText());
  }

  @Test
  void build_filtersUnsupportedHooksAndFormatsLabel() {
    ScenarioMetadata metadata = new ScenarioMetadata(
        "oxygen",
        "Home Oxygen",
        null,
        List.of(new Coding("http://example.org", "E0424", "Stationary Oxygen")),
        List.of("order-select", "patient-view"),
        "DeviceRequest",
        List.of(),
        false,
        false,
        false);

    List<CrdScenario> scenarios = CrdRequestBuilder.build(FhirContext.forR4Cached(), List.of(metadata));
    assertEquals(1, scenarios.size());
    assertEquals(1, scenarios.get(0).variants().size());
    assertEquals("Order Select", scenarios.get(0).variants().get(0).label());
    assertEquals("Order Dispatch", CrdRequestBuilder.formatHookLabel("order-dispatch"));
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
