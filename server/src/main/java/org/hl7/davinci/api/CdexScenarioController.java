package org.hl7.davinci.api;

import java.util.List;
import java.util.Set;

import org.hl7.davinci.scenarios.cdex.CdexScenarioService;
import org.hl7.davinci.scenarios.cdex.CdexScenarioService.PendedClaimDto;
import org.hl7.fhir.r4.model.Parameters;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ca.uhn.fhir.context.FhirContext;

/**
 * REST endpoints backing the CDex attachments testbed page. Lists pended
 * ClaimResponses awaiting documentation and generates ready-to-send
 * $submit-attachment request payloads from live server state.
 */
@RestController
@RequestMapping("/api/cdex")
public class CdexScenarioController {

  private final CdexScenarioService scenarioService;
  private final FhirContext fhirContext;

  public CdexScenarioController(CdexScenarioService scenarioService, FhirContext fhirContext) {
    this.scenarioService = scenarioService;
    this.fhirContext = fhirContext;
  }

  @GetMapping({ "/pended", "/pended/" })
  public List<PendedClaimDto> getPendedClaims() {
    return scenarioService.getPendedClaims();
  }

  /** Returns raw FHIR Parameters JSON for $submit-attachment, directly pasteable into Postman. */
  @GetMapping(value = "/pended/{claimResponseId}/submit-attachment",
      produces = "application/fhir+json")
  public ResponseEntity<String> generateSubmitAttachment(
      @PathVariable("claimResponseId") String claimResponseId,
      @RequestParam(name = "trn", required = false) Set<String> trns,
      @RequestParam(name = "final", defaultValue = "true") boolean finalSubmission) {

    Parameters parameters = scenarioService
        .buildSubmitAttachment(claimResponseId, trns == null ? Set.of() : trns, finalSubmission)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "No pended prior authorization awaiting documentation: " + claimResponseId));

    String json = fhirContext.newJsonParser()
        .setPrettyPrint(true)
        .encodeResourceToString(parameters);

    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/fhir+json"))
        .body(json);
  }
}
