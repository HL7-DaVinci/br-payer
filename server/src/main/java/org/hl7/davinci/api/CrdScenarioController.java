package org.hl7.davinci.api;

import java.util.List;

import org.hl7.davinci.cdshooks.CrdScenarioService;
import org.hl7.davinci.cdshooks.CrdScenarioService.CrdHookVariantDto;
import org.hl7.davinci.cdshooks.CrdScenarioService.CrdScenarioDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoint for CRD test scenarios derived from loaded library resources.
 * Returns pre-built CDS Hooks request JSON for each hook variant.
 */
@RestController
@RequestMapping("/api/crd")
public class CrdScenarioController {

  private final CrdScenarioService scenarioService;

  public CrdScenarioController(CrdScenarioService scenarioService) {
    this.scenarioService = scenarioService;
  }

  @GetMapping("/scenarios")
  public List<CrdScenarioDto> getScenarios() {
    return scenarioService.getScenarios();
  }

  @GetMapping({"/scenarios/{scenarioId}", "/scenarios/{scenarioId}/"})
  public CrdScenarioDto getScenario(@PathVariable("scenarioId") String scenarioId) {
    return scenarioService.findScenario(scenarioId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Scenario not found: " + scenarioId));
  }

  @GetMapping({"/scenarios/{scenarioId}/hooks", "/scenarios/{scenarioId}/hooks/"})
  public List<CrdHookVariantDto> getHooks(@PathVariable("scenarioId") String scenarioId) {
    return scenarioService.findScenario(scenarioId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Scenario not found: " + scenarioId))
        .hooks();
  }

  /** Returns raw CDS Hooks request JSON, directly pasteable into Postman or cURL. */
  @GetMapping(value = {"/scenarios/{scenarioId}/hooks/{hookName}",
      "/scenarios/{scenarioId}/hooks/{hookName}/"},
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> getHookRequest(
      @PathVariable("scenarioId") String scenarioId,
      @PathVariable("hookName") String hookName) {

    String json = scenarioService.findHookRequestJson(scenarioId, hookName)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Hook variant not found: " + scenarioId + "/" + hookName));

    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(json);
  }
}
