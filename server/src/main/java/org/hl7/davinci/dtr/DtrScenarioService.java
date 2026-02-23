package org.hl7.davinci.dtr;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.hl7.davinci.scenarios.DtrRequestBuilder;
import org.hl7.davinci.scenarios.DtrRequestBuilder.DtrScenario;
import org.hl7.davinci.scenarios.DtrRequestBuilder.DtrVariant;
import org.hl7.davinci.scenarios.ScenarioMetadataProvider;
import org.hl7.fhir.r4.model.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;

import ca.uhn.fhir.context.FhirContext;

/**
 * Derives DTR test scenarios from PlanDefinition and Questionnaire resources
 * already loaded in the FHIR repository. Delegates metadata extraction to
 * ScenarioMetadataProvider and request building to DtrRequestBuilder.
 */
@Service
public class DtrScenarioService {

  private static final Logger logger = LoggerFactory.getLogger(DtrScenarioService.class);

  private final ScenarioMetadataProvider metadataProvider;
  private final FhirContext fhirContext;

  public DtrScenarioService(ScenarioMetadataProvider metadataProvider, FhirContext fhirContext) {
    this.metadataProvider = metadataProvider;
    this.fhirContext = fhirContext;
  }

  public List<DtrScenarioDto> getScenarios() {
    return buildScenarios().stream().map(this::toDto).toList();
  }

  public Optional<DtrScenarioDto> findScenario(String scenarioId) {
    return buildScenarios().stream()
        .filter(s -> s.id().equals(scenarioId))
        .map(this::toDto)
        .findFirst();
  }

  public Optional<Parameters> findVariantParameters(String scenarioId, String variantId) {
    String fullId = scenarioId + "-" + variantId;
    return buildScenarios().stream()
        .filter(s -> s.id().equals(scenarioId))
        .flatMap(s -> s.variants().stream())
        .filter(v -> v.id().equals(fullId) || v.id().equals(variantId))
        .map(DtrVariant::parameters)
        .findFirst();
  }

  private List<DtrScenario> buildScenarios() {
    List<DtrScenario> scenarios = DtrRequestBuilder.build(metadataProvider.getMetadata());

    logger.debug("Built {} DTR scenarios", scenarios.size());

    return scenarios;
  }

  private DtrScenarioDto toDto(DtrScenario scenario) {
    List<DtrVariantDto> variantDtos = scenario.variants().stream()
        .map(v -> new DtrVariantDto(
            v.id(), v.label(), v.pathType(),
            fhirContext.newJsonParser().setPrettyPrint(false)
                .encodeResourceToString(v.parameters()),
            v.headers()))
        .toList();

    return new DtrScenarioDto(
        scenario.id(), scenario.name(), scenario.description(),
        scenario.orderType(), scenario.isAdaptive(), scenario.isAdaptiveSearch(),
        variantDtos);
  }

  // ===== DTOs =====

  public record DtrScenarioDto(
      String id,
      String name,
      String description,
      String orderType,
      @JsonProperty("isAdaptive") boolean isAdaptive,
      @JsonProperty("isAdaptiveSearch") boolean isAdaptiveSearch,
      List<DtrVariantDto> variants) {
  }

  public record DtrVariantDto(
      String id,
      String label,
      String pathType,
      @JsonRawValue String parameters,
      Map<String, String> headers) {
  }
}
