package org.hl7.davinci.cdshooks.services;

import java.util.ArrayList;
import java.util.List;

import org.hl7.davinci.cdshooks.error.CdsHooksException;
import org.hl7.davinci.cdshooks.shared.CdsServiceBase;
import org.hl7.davinci.cdshooks.shared.CrdServiceExtension;
import org.hl7.davinci.cdshooks.shared.ResolvedResources;
import org.hl7.davinci.common.ResourceResolver;
import org.hl7.fhir.r4.model.Resource;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.hapi.fhir.cdshooks.api.CdsPrefetchFailureMode;
import ca.uhn.hapi.fhir.cdshooks.api.CdsService;
import ca.uhn.hapi.fhir.cdshooks.api.CdsServicePrefetch;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;

/**
 * CDS Hook service for the order-select hook.
 */
public class OrderSelectService extends CdsServiceBase {

  @CdsService(
    value = "order-select-crd",
    hook = "order-select",
    title = "CRD Order Select Hook",
    description = "Indicates coverage requirements associated with draft orders, including expectations for prior authorization, recommended therapy alternatives, etc.",
    extension = CRD_SERVICE_EXTENSION,
    extensionClass = CrdServiceExtension.class,
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
  })
  public CdsServiceResponseJson handleRequest(CdsServiceRequestJson request) {
    return processRequest(request);
  }

  @Override
  protected String getHookName() {
    return "order-select";
  }

  @Override
  protected void validateRequestInput(CdsServiceRequestJson request) {
    var context = request.getContext();
    String hook = getHookName();

    requireString(context, hook, "userId", true);
    requireString(context, hook, "patientId", true);
    requireString(context, hook, "encounterId", false);
    requireStringList(context, hook, "selections", true);
    requireObject(context, hook, "draftOrders", true);
  }

  @Override
  protected void validateExtractedResources(ResolvedResources context) {
    // Patient validation is handled by HAPI prefetch layer (no failureMode.OMIT)
    // Coverage is optional for supporting hooks - if missing, base class returns empty response

    if (context.getOrders().isEmpty()) {
      throw new CdsHooksException.BadRequestException(
        "draftOrders context is required but was empty, missing, or could not be resolved."
      );
    }
    if (context.getSelections().isEmpty()) {
      throw new CdsHooksException.BadRequestException(
        "selections context is required but was empty, missing, or could not be resolved."
      );
    }
  }

  @Override
  protected List<Resource> selectContextResources(ResolvedResources context) {
    List<Resource> selectedOrders = new ArrayList<>();
    List<String> selections = context.getSelections();

    for (Resource order : context.getOrders()) {
      String resourceRef = ResourceResolver.toRelativeReference(order);
      String resourceId = order.getIdElement().getValue();
      if (resourceRef != null && selections.stream().anyMatch(sel ->
          ResourceResolver.referencesMatch(sel, resourceRef)
          || ResourceResolver.referencesMatchResource(sel, order)
          || sel.equals(resourceId)
          || sel.equals(resourceRef))) {
        selectedOrders.add(order);
      }
    }

    return selectedOrders;
  }
}
