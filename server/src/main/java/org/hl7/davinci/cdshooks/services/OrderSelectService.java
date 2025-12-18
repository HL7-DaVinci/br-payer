package org.hl7.davinci.cdshooks.services;

import java.util.ArrayList;
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
 * CDS Hook service for the order-select hook.
 */
public class OrderSelectService extends CdsServiceBase {

  private List<String> selections;

  @CdsService(value = "order-select-crd", hook = "order-select", title = "CRD Order Select Hook", description = "CRD order-select hook for early coverage requirements discovery", allowAutoFhirClientPrefetch = true, prefetch = {
      @CdsServicePrefetch(value = "user", query = "{{context.userId}}", failureMode = CdsPrefetchFailureMode.OMIT),
      @CdsServicePrefetch(value = "patient", query = "Patient/{{context.patientId}}"),
      @CdsServicePrefetch(value = "coverage", query = "Coverage?patient={{context.patientId}}")
  })
  public CdsServiceResponseJson handleRequest(CdsServiceRequestJson request) {
    this.selections = extractSelections(request);
    return processRequest(request);
  }

  @Override
  protected String getHookName() {
    return "order-select";
  }

  @Override
  protected void validateResourceContext(HookResourceContext context) {
    if (context.getPatient() == null) {
      throw new InvalidRequestException("Patient is required");
    }
    if (context.getCoverage() == null) {
      throw new InvalidRequestException(
          "Coverage is required - CRD clients must provide the primary coverage");
    }
    if (context.getOrders().isEmpty()) {
      throw new InvalidRequestException("draftOrders context is required");
    }
    if (selections == null || selections.isEmpty()) {
      throw new InvalidRequestException("selections context is required");
    }
  }

  @Override
  protected List<Resource> selectContextResources(HookResourceContext context) {
    List<Resource> selectedOrders = new ArrayList<>();

    for (Resource order : context.getOrders()) {
      String resourceRef = order.fhirType() + "/" + order.getIdElement().getIdPart();
      if (selections.stream().anyMatch(sel -> sel.equals(resourceRef) || sel.endsWith(resourceRef))) {
        selectedOrders.add(order);
      }
    }

    return selectedOrders;
  }

  private List<String> extractSelections(CdsServiceRequestJson request) {
    Object selectionsObj = request.getContext().get("selections");
    if (selectionsObj instanceof List<?> selections) {
      return selections.stream().map(Object::toString).toList();
    }
    return List.of();
  }
}
