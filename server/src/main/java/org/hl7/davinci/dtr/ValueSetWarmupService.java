package org.hl7.davinci.dtr;

import static org.hl7.davinci.common.FhirConstants.VSAC_VALUESET_PREFIX;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.r4.model.DataRequirement;
import org.hl7.fhir.r4.model.Library;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;

/**
 * Proactively resolves and persists VSAC-hosted ValueSets referenced by Library dataRequirements
 * after the server is fully started. Eliminates cold-start latency on the first
 * $questionnaire-package request by warming the JPA cache with pre-expanded ValueSets.
 */
@Component
@ConditionalOnExpression("!'${vsac.api-key:}'.isEmpty() && ${dtr.valueset-warmup.enabled:true}")
public class ValueSetWarmupService {

  private static final Logger logger = LoggerFactory.getLogger(ValueSetWarmupService.class);

  private final DaoRegistry daoRegistry;
  private final DtrValueSetCollector valueSetCollector;

  public ValueSetWarmupService(DaoRegistry daoRegistry, DtrValueSetCollector valueSetCollector) {
    this.daoRegistry = daoRegistry;
    this.valueSetCollector = valueSetCollector;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    Thread warmupThread = new Thread(this::warmup, "vsac-valueset-warmup");
    warmupThread.setDaemon(true);
    warmupThread.start();
  }

  void warmup() {
    long start = System.currentTimeMillis();
    Set<String> vsacUrls = discoverVsacUrls();

    if (vsacUrls.isEmpty()) {
      logger.info("VSAC ValueSet warmup: no VSAC ValueSet URLs found in Library dataRequirements");
      return;
    }

    logger.info("Starting VSAC ValueSet warmup: {} unique URLs to resolve", vsacUrls.size());

    int resolved = 0;
    int failed = 0;
    int index = 0;

    for (String url : vsacUrls) {
      index++;
      try {
        List<String> warnings = new ArrayList<>();
        var vs = valueSetCollector.resolveAndPersist(url, warnings);
        if (vs != null && warnings.isEmpty()) {
          resolved++;
          logger.info("Warmed up ValueSet {}/{}: {}", index, vsacUrls.size(), url);
        } else {
          failed++;
          String reason = warnings.isEmpty()
              ? "resolveAndPersist returned no ValueSet"
              : String.join("; ", warnings);
          logger.warn("Failed to warm up ValueSet {}/{}: {} -- {}", index, vsacUrls.size(), url, reason);
        }
      } catch (Exception e) {
        failed++;
        logger.warn("Failed to warm up ValueSet {}/{}: {} -- {}", index, vsacUrls.size(), url, e.getMessage());
      }
    }

    long elapsed = System.currentTimeMillis() - start;
    logger.info("VSAC ValueSet warmup complete: {} resolved, {} failed, {}ms elapsed", resolved, failed, elapsed);
  }

  Set<String> discoverVsacUrls() {
    Set<String> urls = new LinkedHashSet<>();

    SearchParameterMap searchParams = new SearchParameterMap();
    searchParams.setLoadSynchronous(true);
    List<Library> libraries = daoRegistry.getResourceDao(Library.class)
        .searchForResources(searchParams, new SystemRequestDetails());

    for (Library library : libraries) {
      if (!library.hasDataRequirement()) {
        continue;
      }
      for (DataRequirement dr : library.getDataRequirement()) {
        if (!dr.hasCodeFilter()) {
          continue;
        }
        for (DataRequirement.DataRequirementCodeFilterComponent cf : dr.getCodeFilter()) {
          if (cf.hasValueSet() && cf.getValueSet().startsWith(VSAC_VALUESET_PREFIX)) {
            urls.add(cf.getValueSet());
          }
        }
      }
    }

    return urls;
  }
}
