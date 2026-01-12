package org.hl7.davinci.cdshooks.shared;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.jpa.starter.cdshooks.ModuleConfigurationPrefetchSvc;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.hapi.fhir.cdshooks.api.ICdsHooksDaoAuthorizationSvc;
import ca.uhn.hapi.fhir.cdshooks.api.ICdsServiceMethod;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceJson;
import ca.uhn.hapi.fhir.cdshooks.svc.prefetch.CdsPrefetchDaoSvc;
import ca.uhn.hapi.fhir.cdshooks.svc.prefetch.CdsPrefetchFhirClientSvc;
import ca.uhn.hapi.fhir.cdshooks.svc.prefetch.CdsResolutionStrategySvc;

/**
 * Custom prefetch service for CRD that handles optional context variables gracefully.
 *
 * This extends ModuleConfigurationPrefetchSvc to add support for:
 * - Using service's prefetch definitions from the @CdsService annotation
 * - Skipping prefetch templates with missing context variables (e.g., encounterId)
 * - Auto-fetching required prefetch when fhirServer is provided
 */
public class CrdPrefetchSvc extends ModuleConfigurationPrefetchSvc {

  private static final Logger logger = LoggerFactory.getLogger(CrdPrefetchSvc.class);
  private static final Pattern CONTEXT_VAR_PATTERN = Pattern.compile("\\{\\{context\\.([^}]+)\\}\\}");

  private final FhirContext fhirContext;

  public CrdPrefetchSvc(
      CdsResolutionStrategySvc theCdsResolutionStrategySvc,
      CdsPrefetchDaoSvc theResourcePrefetchDao,
      CdsPrefetchFhirClientSvc theResourcePrefetchFhirClient,
      ICdsHooksDaoAuthorizationSvc theCdsHooksDaoAuthorizationSvc,
      IInterceptorBroadcaster theInterceptorBroadcaster) {
    super(
        theCdsResolutionStrategySvc,
        theResourcePrefetchDao,
        theResourcePrefetchFhirClient,
        theCdsHooksDaoAuthorizationSvc,
        theInterceptorBroadcaster);
    this.fhirContext = theResourcePrefetchDao.getFhirContext();
  }

  @Override
  public void augmentRequest(CdsServiceRequestJson request, ICdsServiceMethod serviceMethod) {
    CdsServiceJson serviceSpec = serviceMethod.getCdsServiceJson();
    Set<String> missingPrefetch = this.findMissingPrefetch(serviceSpec, request);

    if (missingPrefetch.isEmpty()) {
      return;
    }

    if (!serviceMethod.isAllowAutoFhirClientPrefetch()) {
      logger.debug("Auto-fetch disabled for service '{}' (allowAutoFhirClientPrefetch=false)", serviceSpec.getId());
      return;
    }

    String fhirServerBase = request.getFhirServer();
    if (fhirServerBase == null || fhirServerBase.isBlank()) {
      return;
    }

    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerBase);
    configureClientAuth(client, request);

    Map<String, String> prefetchDefinitions = serviceSpec.getPrefetch();

    for (String prefetchKey : missingPrefetch) {
      String queryTemplate = prefetchDefinitions.get(prefetchKey);
      if (queryTemplate == null) {
        continue;
      }

      String resolvedQuery = resolveTemplate(queryTemplate, request);
      if (resolvedQuery == null) {
        logger.debug("Skipping prefetch '{}': template has unresolvable context variables", prefetchKey);
        continue;
      }

      try {
        IBaseResource resource = resourceFromUrl(client, resolvedQuery);
        if (resource != null) {
          request.addPrefetch(prefetchKey, resource);
          logger.debug("Auto-fetched prefetch '{}' from: {}", prefetchKey, resolvedQuery);
        }
      } catch (Exception e) {
        logger.debug("Failed to auto-fetch prefetch '{}': {}", prefetchKey, e.getMessage());
      }
    }
  }

  private void configureClientAuth(IGenericClient client, CdsServiceRequestJson request) {
    var authorization = request.getServiceRequestAuthorizationJson();
    if (authorization != null && authorization.getAccessToken() != null) {
      client.registerInterceptor(
          new ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor(authorization.getAccessToken()));
    }
  }

  private String resolveTemplate(String template, CdsServiceRequestJson request) {
    if (template == null) {
      return null;
    }

    String resolved = template;
    Matcher matcher = CONTEXT_VAR_PATTERN.matcher(template);

    while (matcher.find()) {
      String contextKey = matcher.group(1);
      Object value = request.getContext().get(contextKey);

      if (value == null) {
        return null;
      }

      resolved = resolved.replace(matcher.group(0), value.toString());
    }

    return resolved;
  }
}
