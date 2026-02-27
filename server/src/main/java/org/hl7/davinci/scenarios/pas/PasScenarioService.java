package org.hl7.davinci.scenarios.pas;

import java.util.List;
import java.util.Optional;

import org.hl7.davinci.scenarios.pas.PasRequestBuilder.PasScenario;
import org.hl7.davinci.scenarios.pas.PasRequestBuilder.PasVariant;
import org.hl7.davinci.scenarios.ScenarioMetadataProvider;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonRawValue;

import ca.uhn.fhir.context.FhirContext;

/**
 * Derives PAS test scenarios from PlanDefinition resources already loaded in
 * the FHIR repository. Delegates metadata extraction to ScenarioMetadataProvider
 * and request building to PasRequestBuilder.
 */
@Service
public class PasScenarioService {

  private static final Logger logger = LoggerFactory.getLogger(PasScenarioService.class);

  private final ScenarioMetadataProvider metadataProvider;
  private final FhirContext fhirContext;

  public PasScenarioService(ScenarioMetadataProvider metadataProvider, FhirContext fhirContext) {
    this.metadataProvider = metadataProvider;
    this.fhirContext = fhirContext;
  }

  public List<PasScenarioDto> getScenarios() {
    return buildScenarios().stream().map(this::toDto).toList();
  }

  public Optional<PasScenarioDto> findScenario(String scenarioId) {
    return buildScenarios().stream()
        .filter(s -> s.id().equals(scenarioId))
        .map(this::toDto)
        .findFirst();
  }

  public Optional<Bundle> findVariantBundle(String scenarioId, String variantId) {
    return buildScenarios().stream()
        .filter(s -> s.id().equals(scenarioId))
        .flatMap(s -> s.variants().stream())
        .filter(v -> v.id().equals(variantId) || v.id().equals(scenarioId + "-" + variantId))
        .map(PasVariant::bundle)
        .findFirst();
  }

  private List<PasScenario> buildScenarios() {
    List<PasScenario> scenarios = PasRequestBuilder.build(metadataProvider.getMetadata());
    logger.debug("Built {} PAS scenarios", scenarios.size());
    return scenarios;
  }

  private PasScenarioDto toDto(PasScenario scenario) {
    List<PasVariantDto> variantDtos = scenario.variants().stream()
        .map(v -> new PasVariantDto(
            v.id(), v.label(), v.operation(), v.payloadType(),
            fhirContext.newJsonParser().setPrettyPrint(false)
                .encodeResourceToString(v.bundle())))
        .toList();

    return new PasScenarioDto(
        scenario.id(), scenario.name(), scenario.description(),
        scenario.orderType(), variantDtos);
  }

  // ===== DTOs =====

  public record PasScenarioDto(
      String id,
      String name,
      String description,
      String orderType,
      List<PasVariantDto> variants) {
  }

  public record PasVariantDto(
      String id,
      String label,
      String operation,
      String payloadType,
      @JsonRawValue String bundle) {
  }
}
