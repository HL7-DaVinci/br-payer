package org.hl7.davinci.scenarios;

import java.util.ArrayList;
import java.util.List;

import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.Questionnaire;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;

/**
 * Fetches PlanDefinition and Questionnaire resources from the FHIR repository
 * and delegates to LibraryScenarioScanner to produce ScenarioMetadata. Shared
 * by both CRD and DTR scenario services to eliminate duplicated fetch logic.
 */
@Service
public class ScenarioMetadataProvider {

  private final DaoRegistry daoRegistry;

  public ScenarioMetadataProvider(DaoRegistry daoRegistry) {
    this.daoRegistry = daoRegistry;
  }

  public List<ScenarioMetadata> getMetadata() {
    List<Questionnaire> questionnaires = fetchAll(Questionnaire.class);
    List<PlanDefinition> planDefinitions = fetchAll(PlanDefinition.class);
    return LibraryScenarioScanner.scan(questionnaires, planDefinitions);
  }

  <T extends IBaseResource> List<T> fetchAll(Class<T> type) {
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
}
