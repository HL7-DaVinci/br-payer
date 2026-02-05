package org.hl7.davinci.cdshooks.services;

import java.util.List;

import org.hl7.davinci.cdshooks.error.CdsHooksException;
import org.hl7.davinci.cdshooks.shared.CdsServiceBase;
import org.hl7.davinci.cdshooks.shared.CrdServiceExtension;
import org.hl7.davinci.cdshooks.shared.ResolvedResources;
import org.hl7.davinci.cdshooks.shared.ResourceResolver;
import org.hl7.fhir.r4.model.Resource;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.hapi.fhir.cdshooks.api.CdsPrefetchFailureMode;
import ca.uhn.hapi.fhir.cdshooks.api.CdsService;
import ca.uhn.hapi.fhir.cdshooks.api.CdsServicePrefetch;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;

/**
 * CDS Hook service for the order-dispatch hook.
 *
 * This is a primary hook that fires when an order's intended performer is determined
 * after order creation, enabling in-network/out-of-network coverage determinations.
 */
public class OrderDispatchService extends CdsServiceBase {

  @CdsService(
    value = "order-dispatch-crd",
    hook = "order-dispatch",
    title = "CRD Order Dispatch Hook",
    description = "Coverage guidance when dispatching orders to a performer",
    extension = CRD_SERVICE_EXTENSION,
    extensionClass = CrdServiceExtension.class,
    allowAutoFhirClientPrefetch = true,
    prefetch = {
      @CdsServicePrefetch(value = "patient", query = "Patient/{{context.patientId}}"),
      @CdsServicePrefetch(value = "coverage", query = "Coverage?patient={{context.patientId}}&status=active"),
      @CdsServicePrefetch(value = "encounter", query = "Encounter/{{context.encounterId}}", failureMode = CdsPrefetchFailureMode.OMIT),
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
    return "order-dispatch";
  }

  @Override
  protected void validateRequestInput(CdsServiceRequestJson request) {
    var context = request.getContext();
    String hook = getHookName();

    requireString(context, hook, "patientId", true);
    requireStringList(context, hook, "dispatchedOrders", true);
    requireString(context, hook, "performer", true);
    requireObjectList(context, hook, "fulfillmentTasks", false);
  }

  @Override
  protected void validateExtractedResources(ResolvedResources context) {
    if (context.getOrders().isEmpty()) {
      throw new CdsHooksException.BadRequestException(
        "dispatchedOrders context is required but was empty, missing, or could not be resolved.");
    }

    if (context.getPractitioners().isEmpty() && context.getPractitionerRoles().isEmpty()
        && context.getOrganizations().isEmpty() && context.getCareTeams().isEmpty()
        && context.getLocations().isEmpty()) {
      throw new CdsHooksException.BadRequestException(
          "performer context is required but was empty, missing, or could not be resolved.");
    }

    // Per CDS Hooks order-dispatch spec: "If Tasks are provided, each will be for a
    // separate order and SHALL reference one of the dispatched-orders."
    if (!context.getTasks().isEmpty()) {
      for (var task : context.getTasks()) {
        if (!task.hasFocus() || !task.getFocus().hasReference()) {
          throw new CdsHooksException.BadRequestException(
              "fulfillmentTasks entries must reference one of the dispatchedOrders.");
        }

        boolean matches = context.getOrders().stream()
            .anyMatch(order -> ResourceResolver.referencesMatchResource(task.getFocus().getReference(), order));

        if (!matches) {
          throw new CdsHooksException.BadRequestException(
              "fulfillmentTasks entries must reference one of the dispatchedOrders.");
        }
      }
    }
  }

  @Override
  protected List<Resource> selectContextResources(ResolvedResources context) {
    return context.getOrders();
  }
}
