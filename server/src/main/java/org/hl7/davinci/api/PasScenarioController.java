package org.hl7.davinci.api;

import java.util.List;

import org.hl7.davinci.pas.PasScenarioService;
import org.hl7.davinci.pas.PasScenarioService.PasScenarioDto;
import org.hl7.davinci.pas.PasScenarioService.PasVariantDto;
import org.hl7.fhir.r4.model.Bundle;
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
 * REST endpoint for PAS test scenarios derived from loaded library resources.
 * Returns pre-built $submit/$inquire request Bundles for each scenario variant.
 */
@RestController
@RequestMapping("/api/pas")
public class PasScenarioController {

  private final PasScenarioService scenarioService;
  private final FhirContext fhirContext;

  public PasScenarioController(PasScenarioService scenarioService, FhirContext fhirContext) {
    this.scenarioService = scenarioService;
    this.fhirContext = fhirContext;
  }

  @GetMapping("/scenarios")
  public List<PasScenarioDto> getScenarios() {
    return scenarioService.getScenarios();
  }

  @GetMapping({ "/scenarios/{scenarioId}", "/scenarios/{scenarioId}/" })
  public PasScenarioDto getScenario(@PathVariable("scenarioId") String scenarioId) {
    return scenarioService.findScenario(scenarioId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Scenario not found: " + scenarioId));
  }

  @GetMapping({ "/scenarios/{scenarioId}/variants", "/scenarios/{scenarioId}/variants/" })
  public List<PasVariantDto> getVariants(@PathVariable("scenarioId") String scenarioId) {
    return scenarioService.findScenario(scenarioId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Scenario not found: " + scenarioId))
        .variants();
  }

  /** Returns raw FHIR Bundle JSON for the specified variant, directly pasteable into Postman. */
  @GetMapping(value = "/scenarios/{scenarioId}/variants/{variantId}",
      produces = "application/fhir+json")
  public ResponseEntity<String> getVariant(
      @PathVariable("scenarioId") String scenarioId,
      @PathVariable("variantId") String variantId) {

    Bundle bundle = scenarioService.findVariantBundle(scenarioId, variantId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Variant not found: " + scenarioId + "/" + variantId));

    String json = fhirContext.newJsonParser()
        .setPrettyPrint(true)
        .encodeResourceToString(bundle);

    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/fhir+json"))
        .body(json);
  }
}
