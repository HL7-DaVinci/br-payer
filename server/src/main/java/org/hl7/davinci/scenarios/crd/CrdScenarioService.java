package org.hl7.davinci.scenarios.crd;

import java.util.List;
import java.util.Optional;

import org.hl7.davinci.scenarios.crd.CrdRequestBuilder.CrdHookVariant;
import org.hl7.davinci.scenarios.crd.CrdRequestBuilder.CrdScenario;
import org.hl7.davinci.scenarios.ScenarioMetadataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonRawValue;

import ca.uhn.fhir.context.FhirContext;

/**
 * Derives CRD test scenarios from PlanDefinition and Questionnaire resources
 * loaded in the FHIR repository. Delegates metadata extraction to
 * ScenarioMetadataProvider and request building to CrdRequestBuilder.
 */
@Service
public class CrdScenarioService {

  private static final Logger logger = LoggerFactory.getLogger(CrdScenarioService.class);

  private final ScenarioMetadataProvider metadataProvider;
  private final FhirContext fhirContext;

  public CrdScenarioService(ScenarioMetadataProvider metadataProvider, FhirContext fhirContext) {
    this.metadataProvider = metadataProvider;
    this.fhirContext = fhirContext;
  }

  public List<CrdScenarioDto> getScenarios() {
    return buildScenarios().stream().map(this::toDto).toList();
  }

  public Optional<CrdScenarioDto> findScenario(String scenarioId) {
    return buildScenarios().stream()
        .filter(s -> s.id().equals(scenarioId))
        .map(this::toDto)
        .findFirst();
  }

  public Optional<String> findHookRequestJson(String scenarioId, String hookName) {
    return buildScenarios().stream()
        .filter(s -> s.id().equals(scenarioId))
        .flatMap(s -> s.variants().stream())
        .filter(v -> v.hookName().equals(hookName))
        .map(CrdHookVariant::requestJson)
        .findFirst();
  }

  private List<CrdScenario> buildScenarios() {
    List<CrdScenario> scenarios = CrdRequestBuilder.build(fhirContext, metadataProvider.getMetadata());

    logger.debug("Built {} CRD scenarios", scenarios.size());

    return scenarios;
  }

  private CrdScenarioDto toDto(CrdScenario scenario) {
    List<CrdHookVariantDto> hookDtos = scenario.variants().stream()
        .map(v -> new CrdHookVariantDto(v.id(), v.hookName(), v.label(), v.requestJson()))
        .toList();

    return new CrdScenarioDto(
        scenario.id(), scenario.name(), scenario.description(), hookDtos);
  }

  // ===== DTOs =====

  public record CrdScenarioDto(String id, String name, String description,
      List<CrdHookVariantDto> hooks) {}

  public record CrdHookVariantDto(String id, String hookName, String label,
      @JsonRawValue String requestJson) {}
}
