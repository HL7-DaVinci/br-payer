package org.hl7.davinci.cdshooks.shared;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.prefetch.CdsHookPrefetchPointcutContextJson;

@Interceptor
public class CdsPrefetchInterceptor {

  private static final Logger logger = LoggerFactory.getLogger(CdsPrefetchInterceptor.class);

  @Hook(Pointcut.CDS_HOOK_PREFETCH_REQUEST)
  public void prefetchRequest(
      CdsHookPrefetchPointcutContextJson context,
      CdsServiceRequestJson request) {
    logger.info("CDS Hook prefetch request for query: {}", context.getQuery());
  }

  @Hook(Pointcut.CDS_HOOK_PREFETCH_RESPONSE)
  public void prefetchResponse(
      CdsHookPrefetchPointcutContextJson context,
      CdsServiceRequestJson request,
      IBaseResource resource) {
    logger.info("CDS Hook prefetch response for resource: {}", resource.getIdElement());
  }

  @Hook(Pointcut.CDS_HOOK_PREFETCH_FAILED)
  public void prefetchFailed(
      CdsHookPrefetchPointcutContextJson context,
      CdsServiceRequestJson request,
      Exception exception) {
    logger.info("CDS Hook prefetch failed with exception: {}", exception.getMessage());
  
  }

}