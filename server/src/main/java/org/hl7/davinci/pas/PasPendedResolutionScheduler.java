package org.hl7.davinci.pas;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.hl7.fhir.r4.model.Meta;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;

@Component
@EnableScheduling
@EnableConfigurationProperties(PasProperties.class)
public class PasPendedResolutionScheduler {

  private static final Logger log = LoggerFactory.getLogger(PasPendedResolutionScheduler.class);
  private static final int SEARCH_PAGE_SIZE = 100;

  private final DaoRegistry daoRegistry;
  private final PasResponseBuilder responseBuilder;
  private final PasProperties pasProperties;

  public PasPendedResolutionScheduler(DaoRegistry daoRegistry, PasResponseBuilder responseBuilder,
      PasProperties pasProperties) {
    this.daoRegistry = daoRegistry;
    this.responseBuilder = responseBuilder;
    this.pasProperties = pasProperties;
  }

  @Scheduled(fixedDelayString = "#{${pas.pended-resolution-delay-seconds:30} * 1000}")
  public void resolvePendedAuthorizations() {
    SearchParameterMap params = new SearchParameterMap();
    params.add(Constants.PARAM_TAG,
        new TokenParam(PasSubmitService.PENDED_TAG_SYSTEM, PasSubmitService.PENDED_TAG_CODE));

    IBundleProvider results = daoRegistry.getResourceDao(ClaimResponse.class)
        .search(params, new SystemRequestDetails());
    List<IBaseResource> resources = loadSearchResults(results);

    if (resources.isEmpty()) {
      return;
    }

    Date cutoff = new Date(System.currentTimeMillis()
        - (pasProperties.pendedResolutionDelaySeconds() * 1000L));

    for (IBaseResource resource : resources) {
      ClaimResponse cr = (ClaimResponse) resource;
      if (cr.getMeta().getLastUpdated() != null && cr.getMeta().getLastUpdated().before(cutoff)) {
        resolveAuthorization(cr);
      }
    }
  }

  private List<IBaseResource> loadSearchResults(IBundleProvider results) {
    Integer totalSize = results.size();
    if (totalSize != null) {
      if (totalSize <= 0) {
        return List.of();
      }
      return results.getResources(0, totalSize);
    }

    // Some DAO implementations do not provide total size; iterate pages until exhausted.
    List<IBaseResource> collected = new ArrayList<>();
    int fromIndex = 0;
    while (true) {
      List<IBaseResource> page = results.getResources(fromIndex, fromIndex + SEARCH_PAGE_SIZE);
      if (page.isEmpty()) {
        break;
      }
      collected.addAll(page);
      if (page.size() < SEARCH_PAGE_SIZE) {
        break;
      }
      fromIndex += page.size();
    }
    return collected;
  }

  void resolveAuthorization(ClaimResponse pendedCr) {
    responseBuilder.resolvePendedItems(pendedCr, pasProperties.authorizationNumberPrefix());

    var crDao = daoRegistry.getResourceDao(ClaimResponse.class);
    crDao.update(pendedCr, new SystemRequestDetails());

    // HAPI JPA treats tags as additive across versions, so the update alone
    // won't remove the pended tag. Use metaDeleteOperation to explicitly remove it.
    Meta tagToRemove = new Meta();
    tagToRemove.addTag(PasSubmitService.PENDED_TAG_SYSTEM, PasSubmitService.PENDED_TAG_CODE, null);
    crDao.metaDeleteOperation(pendedCr.getIdElement().toUnqualifiedVersionless(),
        tagToRemove, new SystemRequestDetails());

    log.info("Resolved pended authorization: ClaimResponse/{}", pendedCr.getIdElement().getIdPart());
  }
}
