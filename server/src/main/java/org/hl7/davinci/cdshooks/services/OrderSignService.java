package org.hl7.davinci.cdshooks.services;

import java.util.List;

import org.hl7.davinci.cdshooks.shared.CdsServiceBase;
import org.hl7.davinci.cdshooks.shared.HookResourceContext;
import org.hl7.fhir.r4.model.Resource;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.hapi.fhir.cdshooks.api.CdsPrefetchFailureMode;
import ca.uhn.hapi.fhir.cdshooks.api.CdsService;
import ca.uhn.hapi.fhir.cdshooks.api.CdsServicePrefetch;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;

/**
 * CDS Hook service for the order-sign hook.
 */
public class OrderSignService extends CdsServiceBase {

  @CdsService(
      value = "order-sign-crd",
      hook = "order-sign",
      title = "CRD Order Sign Hook",
      description = "CRD order-sign hook for coverage requirements discovery",
      allowAutoFhirClientPrefetch = true,
      prefetch = {
          @CdsServicePrefetch(value = "user", query = "{{context.userId}}", failureMode = CdsPrefetchFailureMode.OMIT),
          @CdsServicePrefetch(value = "patient", query = "Patient/{{context.patientId}}"),
          @CdsServicePrefetch(value = "coverage", query = "Coverage?patient={{context.patientId}}&status=active"),
      }
  )
  public CdsServiceResponseJson handleRequest(CdsServiceRequestJson request) {
    return processRequest(request);
  }

  @Override
  protected String getHookName() {
    return "order-sign";
  }

  @Override
  protected void validateResourceContext(HookResourceContext context) {
    if (context.getPatient() == null) {
      throw new InvalidRequestException("Patient is required");
    }
    if (context.getCoverage() == null) {
      throw new InvalidRequestException("Coverage is required - CRD clients must provide the primary coverage");
    }
    if (context.getOrders().isEmpty()) {
      throw new InvalidRequestException("draftOrders context is required");
    }
  }

  @Override
  protected List<Resource> selectContextResources(HookResourceContext context) {
    return context.getOrders();
  }
}
