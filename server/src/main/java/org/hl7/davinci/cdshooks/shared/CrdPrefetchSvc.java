package org.hl7.davinci.cdshooks.shared;

import java.util.Locale;
import java.util.Map;

import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.util.OperationOutcomeUtil;
import ca.uhn.hapi.fhir.cdshooks.api.CdsPrefetchFailureMode;
import ca.uhn.hapi.fhir.cdshooks.api.ICdsHooksDaoAuthorizationSvc;
import ca.uhn.hapi.fhir.cdshooks.api.ICdsServiceMethod;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceJson;
import ca.uhn.hapi.fhir.cdshooks.svc.prefetch.CdsPrefetchDaoSvc;
import ca.uhn.hapi.fhir.cdshooks.svc.prefetch.CdsPrefetchFhirClientSvc;
import ca.uhn.hapi.fhir.cdshooks.svc.prefetch.CdsPrefetchSvc;
import ca.uhn.hapi.fhir.cdshooks.svc.prefetch.CdsResolutionStrategySvc;
import ca.uhn.hapi.fhir.cdshooks.svc.prefetch.PrefetchTemplateUtil;

/**
 * Custom prefetch service for CRD that avoids errors on missing optional
 * context variables.
 *
 * This delegates to HAPI's CdsPrefetchSvc for all standard behavior and only
 * suppresses OMIT-prefetch when the context key is absent.
 *
 * Context type validation is handled by each service class via validateRequestInput().
 */
public class CrdPrefetchSvc extends CdsPrefetchSvc {

  private static final Logger logger = LoggerFactory.getLogger(CrdPrefetchSvc.class);
  private static final String MISSING_CONTEXT_PHRASE = "did not provide a value for key <";
  private static final String CONTEXT_EMPTY_PHRASE = "request context was empty";

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
    if (serviceSpec != null) {
      suppressOmitPrefetchWithMissingContext(request, serviceSpec);
    }
    super.augmentRequest(request, serviceMethod);
  }

  /**
   * Suppresses OMIT-prefetch keys when the context variable they reference is missing.
   * This prevents HAPI from throwing an error for optional context variables.
   */
  private void suppressOmitPrefetchWithMissingContext(CdsServiceRequestJson request, CdsServiceJson serviceSpec) {
    Map<String, String> prefetch = serviceSpec.getPrefetch();
    if (prefetch == null || prefetch.isEmpty()) {
      return;
    }

    for (Map.Entry<String, String> entry : prefetch.entrySet()) {
      String prefetchKey = entry.getKey();
      if (prefetchKey == null || prefetchKey.isBlank()) {
        continue;
      }
      if (request.getPrefetch(prefetchKey) != null) {
        continue;
      }
      if (serviceSpec.getPrefetchFailureMode(prefetchKey) != CdsPrefetchFailureMode.OMIT) {
        continue;
      }

      String template = entry.getValue();
      if (template == null || template.isBlank()) {
        continue;
      }

      try {
        PrefetchTemplateUtil.substituteTemplate(template, request.getContext(), fhirContext);
      } catch (ClassCastException e) {
        // Safety net: invalid context type (e.g., array instead of string)
        // This should be caught earlier by validateRequestInput in the service class,
        // but we handle it here as well to prevent 500 errors.
        throw new InvalidRequestException(
            "Invalid context type while evaluating prefetch template for key '" + prefetchKey + "'.", e);
      } catch (InvalidRequestException e) {
        if (isMissingContextException(e)) {
          logger.info("Skipping prefetch '{}' due to missing optional context", prefetchKey);
          addOmittedPrefetchPlaceholder(request, prefetchKey);
          continue;
        }
        throw e;
      }
    }
  }

  private boolean isMissingContextException(InvalidRequestException exception) {
    String message = exception.getMessage();
    if (message == null) {
      return false;
    }
    return message.contains(MISSING_CONTEXT_PHRASE)
        || message.toLowerCase(Locale.ROOT).contains(CONTEXT_EMPTY_PHRASE);
  }

  private void addOmittedPrefetchPlaceholder(CdsServiceRequestJson request, String prefetchKey) {
    IBaseOperationOutcome outcome = OperationOutcomeUtil.newInstance(fhirContext);
    request.addPrefetch(prefetchKey, outcome);
  }
}
