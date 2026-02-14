package org.hl7.davinci.dtr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.hl7.davinci.scenarios.DtrRequestBuilder;
import org.hl7.davinci.scenarios.DtrRequestBuilder.DtrScenario;
import org.hl7.davinci.scenarios.DtrRequestBuilder.DtrVariant;
import org.hl7.davinci.scenarios.LibraryScenarioScanner;
import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.Questionnaire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;

/**
 * Derives DTR test scenarios from PlanDefinition and Questionnaire resources
 * already loaded in the FHIR repository. Delegates metadata extraction to
 * LibraryScenarioScanner and request building to DtrRequestBuilder.
 */
@Service
public class DtrScenarioService {

  private static final Logger logger = LoggerFactory.getLogger(DtrScenarioService.class);

  private final DaoRegistry daoRegistry;
  private final FhirContext fhirContext;

  public DtrScenarioService(DaoRegistry daoRegistry, FhirContext fhirContext) {
    this.daoRegistry = daoRegistry;
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
    List<Questionnaire> questionnaires = fetchAll(Questionnaire.class);
    List<PlanDefinition> planDefinitions = fetchAll(PlanDefinition.class);

    List<ScenarioMetadata> metadata = LibraryScenarioScanner.scan(questionnaires, planDefinitions);
    List<DtrScenario> scenarios = DtrRequestBuilder.build(metadata);

    logger.debug("Built {} DTR scenarios from {} questionnaires and {} PlanDefinitions",
        scenarios.size(), questionnaires.size(), planDefinitions.size());

    return scenarios;
  }

  private <T extends IBaseResource> List<T> fetchAll(Class<T> type) {
    IBundleProvider results = daoRegistry.getResourceDao(type)
        .search(new SearchParameterMap(), new SystemRequestDetails());

    Integer total = results.size();
    if (total != null) {
      return results.getResources(0, total).stream()
          .filter(type::isInstance)
          .map(type::cast)
          .toList();
    }

    List<T> resources = new ArrayList<>();
    int from = 0;
    int pageSize = 200;
    while (true) {
      List<T> batch = results.getResources(from, from + pageSize).stream()
          .filter(type::isInstance)
          .map(type::cast)
          .toList();
      if (batch.isEmpty()) {
        break;
      }
      resources.addAll(batch);
      from += batch.size();
    }
    return resources;
  }

  private DtrScenarioDto toDto(DtrScenario scenario) {
    List<DtrVariantDto> variantDtos = scenario.variants().stream()
        .map(v -> new DtrVariantDto(
            v.id(), v.label(), v.pathType(),
            fhirContext.newJsonParser().setPrettyPrint(false)
                .encodeResourceToString(v.parameters())))
        .toList();

    return new DtrScenarioDto(
        scenario.id(), scenario.name(), scenario.description(),
        scenario.orderType(), scenario.isAdaptive(), variantDtos);
  }

  // ===== DTOs =====

  public record DtrScenarioDto(
      String id,
      String name,
      String description,
      String orderType,
      @JsonProperty("isAdaptive") boolean isAdaptive,
      List<DtrVariantDto> variants) {
  }

  public record DtrVariantDto(
      String id,
      String label,
      String pathType,
      @JsonRawValue String parameters) {
  }
}
