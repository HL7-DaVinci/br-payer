package org.hl7.davinci.common;

import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.ValueSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDaoValueSet;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;

/**
 * Resolves ValueSets by canonical URL from the local JPA store, falling back to the
 * validation support chain (which includes VSAC when configured). Externally fetched
 * ValueSets are expanded and persisted locally so subsequent lookups are fast.
 *
 * This is shared infrastructure used by both DTR ValueSet collection and the
 * VSAC warmup service.
 */
@Component
public class VsacValueSetResolver {

  private static final Logger logger = LoggerFactory.getLogger(VsacValueSetResolver.class);

  private final DaoRegistry daoRegistry;
  private final IValidationSupport validationSupport;

  public VsacValueSetResolver(DaoRegistry daoRegistry, IValidationSupport validationSupport) {
    this.daoRegistry = daoRegistry;
    this.validationSupport = validationSupport;
  }

  /**
   * Resolve a ValueSet by canonical URL: first from JPA, then via the validation support chain
   * (which includes VSAC). If fetched externally, expands and persists to JPA so subsequent
   * lookups are local. Returns null if the ValueSet cannot be resolved.
   */
  public ValueSet resolveAndPersist(String url, List<String> warnings) {
    ValueSet vs = FhirUtil.resolveByCanonical(daoRegistry, ValueSet.class, url);

    if (vs == null && validationSupport != null) {
      String baseUrl = FhirUtil.parseCanonical(url)[0];
      IBaseResource fetched = validationSupport.fetchValueSet(baseUrl);
      if (fetched instanceof ValueSet) {
        vs = (ValueSet) fetched;
        logger.debug("ValueSet resolved via validation support: {}", url);
        persistExternalValueSet(vs, warnings);
      }
    }

    if (vs == null) {
      String warning = "ValueSet not found: " + url;
      logger.warn(warning);
      warnings.add(warning);
    }

    return vs;
  }

  /**
   * Persist an externally-fetched ValueSet (e.g. from VSAC) to the JPA store so downstream
   * consumers like the CQL engine's RepositoryTerminologyProvider can find it via repository search.
   * Expands the ValueSet first so the CQL engine can use pre-existing expansion elements.
   */
  @SuppressWarnings("unchecked")
  private void persistExternalValueSet(ValueSet vs, List<String> warnings) {
    IFhirResourceDaoValueSet<ValueSet> vsDao =
        (IFhirResourceDaoValueSet<ValueSet>) daoRegistry.getResourceDao(ValueSet.class);

    if (!vs.hasExpansion()) {
      try {
        ValueSet expanded = vsDao.expand(vs, null);
        if (expanded != null && expanded.hasExpansion()) {
          vs.setExpansion(expanded.getExpansion());
        }
      } catch (Exception e) {
        String warning = "ValueSet expansion failed during persist for " + vs.getUrl() + ": " + e.getMessage();
        logger.warn(warning);
        warnings.add(warning);
      }
    }

    try {
      vsDao.update(vs, new SystemRequestDetails());
      logger.debug("Persisted external ValueSet to JPA store: {}", vs.getUrl());
    } catch (Exception e) {
      String warning = "Failed to persist external ValueSet " + vs.getUrl() + ": " + e.getMessage();
      logger.warn(warning);
      warnings.add(warning);
    }
  }
}
