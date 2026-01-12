package org.hl7.davinci.cdshooks.services;

import java.util.List;

import org.hl7.davinci.cdshooks.error.CdsHooksException;
import org.hl7.davinci.cdshooks.shared.CdsServiceBase;
import org.hl7.davinci.cdshooks.shared.HookResourceContext;
import org.hl7.fhir.r4.model.Resource;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
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
      description = "Guidance related to orders being signed",
      allowAutoFhirClientPrefetch = true,
      prefetch = {
        @CdsServicePrefetch(value = "patient", query = "Patient/{{context.patientId}}"),
        @CdsServicePrefetch(value = "coverage", query = "Coverage?patient={{context.patientId}}&status=active"),
        @CdsServicePrefetch(value = "encounter", query = "Encounter/{{context.encounterId}}", failureMode = CdsPrefetchFailureMode.OMIT),
        @CdsServicePrefetch(value = "practitionerRoles", query = "PractitionerRole?practitioner={{context.userId}}", failureMode = CdsPrefetchFailureMode.OMIT),
        @CdsServicePrefetch(value = "practitioner", query = "{{context.userId}}", failureMode = CdsPrefetchFailureMode.OMIT),
        // Historical orders for duplicate therapy detection, step therapy, and frequency limits
        @CdsServicePrefetch(value = "deviceHistory", query = "DeviceRequest?patient={{context.patientId}}&status=active,on-hold,completed", failureMode = CdsPrefetchFailureMode.OMIT),
        @CdsServicePrefetch(value = "medicationHistory", query = "MedicationRequest?patient={{context.patientId}}&status=active,completed", failureMode = CdsPrefetchFailureMode.OMIT),
        @CdsServicePrefetch(value = "serviceHistory", query = "ServiceRequest?patient={{context.patientId}}&status=active,completed", failureMode = CdsPrefetchFailureMode.OMIT)
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
    // Patient validation is handled by HAPI prefetch layer (no failureMode.OMIT)
    // Coverage validation is handled in base class for primary hooks

    if (context.getOrders().isEmpty()) {
      throw new CdsHooksException.BadRequestException(
        "draftOrders context is required but was empty or missing."
      );
    }
  }

  @Override
  protected List<Resource> selectContextResources(HookResourceContext context) {
    return context.getOrders();
  }
}
