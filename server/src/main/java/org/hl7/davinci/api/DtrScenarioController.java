package org.hl7.davinci.api;

import java.util.List;

import org.hl7.davinci.scenarios.dtr.DtrScenarioService;
import org.hl7.davinci.scenarios.dtr.DtrScenarioService.DtrScenarioDto;
import org.hl7.davinci.scenarios.dtr.DtrScenarioService.DtrVariantDto;
import org.hl7.fhir.r4.model.Parameters;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ca.uhn.fhir.context.FhirContext;

/**
 * REST endpoint for DTR test scenarios derived from loaded library resources.
 * Returns pre-built $questionnaire-package request Parameters for each scenario
 * variant.
 */
@RestController
@RequestMapping("/api/dtr")
public class DtrScenarioController {

  private final DtrScenarioService scenarioService;
  private final FhirContext fhirContext;

  public DtrScenarioController(DtrScenarioService scenarioService, FhirContext fhirContext) {
    this.scenarioService = scenarioService;
    this.fhirContext = fhirContext;
  }

  @GetMapping("/scenarios")
  public List<DtrScenarioDto> getScenarios() {
    return scenarioService.getScenarios();
  }

  @GetMapping({ "/scenarios/{scenarioId}", "/scenarios/{scenarioId}/" })
  public DtrScenarioDto getScenario(@PathVariable("scenarioId") String scenarioId) {
    return scenarioService.findScenario(scenarioId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Scenario not found: " + scenarioId));
  }

  @GetMapping({ "/scenarios/{scenarioId}/variants", "/scenarios/{scenarioId}/variants/" })
  public List<DtrVariantDto> getVariants(@PathVariable("scenarioId") String scenarioId) {
    return scenarioService.findScenario(scenarioId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Scenario not found: " + scenarioId))
        .variants();
  }

  /** Returns raw FHIR Parameters JSON, directly pasteable into Postman. */
  @GetMapping(value = "/scenarios/{scenarioId}/variants/{variantId}", produces = "application/fhir+json")
  public ResponseEntity<String> getVariant(
      @PathVariable("scenarioId") String scenarioId,
      @PathVariable("variantId") String variantId) {

    Parameters params = scenarioService.findVariantParameters(scenarioId, variantId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Variant not found: " + scenarioId + "/" + variantId));

    String json = fhirContext.newJsonParser()
        .setPrettyPrint(true)
        .encodeResourceToString(params);

    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/fhir+json"))
        .body(json);
  }
}
