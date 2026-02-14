package org.hl7.davinci.cdshooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.hl7.davinci.scenarios.CrdRequestBuilder;
import org.hl7.davinci.scenarios.CrdRequestBuilder.CrdHookVariant;
import org.hl7.davinci.scenarios.CrdRequestBuilder.CrdScenario;
import org.hl7.davinci.scenarios.LibraryScenarioScanner;
import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.Questionnaire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonRawValue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;

/**
 * Derives CRD test scenarios from PlanDefinition and Questionnaire resources
 * loaded in the FHIR repository. Delegates metadata extraction to
 * LibraryScenarioScanner and request building to CrdRequestBuilder.
 */
@Service
public class CrdScenarioService {

  private static final Logger logger = LoggerFactory.getLogger(CrdScenarioService.class);

  private final DaoRegistry daoRegistry;
  private final FhirContext fhirContext;

  public CrdScenarioService(DaoRegistry daoRegistry, FhirContext fhirContext) {
    this.daoRegistry = daoRegistry;
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
    List<Questionnaire> questionnaires = fetchAll(Questionnaire.class);
    List<PlanDefinition> planDefinitions = fetchAll(PlanDefinition.class);

    List<ScenarioMetadata> metadata = LibraryScenarioScanner.scan(questionnaires, planDefinitions);
    List<CrdScenario> scenarios = CrdRequestBuilder.build(fhirContext, metadata);

    logger.debug("Built {} CRD scenarios from {} questionnaires and {} PlanDefinitions",
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
