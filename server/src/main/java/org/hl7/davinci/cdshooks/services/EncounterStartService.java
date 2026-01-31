package org.hl7.davinci.cdshooks.services;

import java.util.List;

import org.hl7.davinci.cdshooks.error.CdsHooksException;
import org.hl7.davinci.cdshooks.shared.CdsServiceBase;
import org.hl7.davinci.cdshooks.shared.CrdServiceExtension;
import org.hl7.davinci.cdshooks.shared.HookResourceContext;
import org.hl7.fhir.r4.model.Resource;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.hapi.fhir.cdshooks.api.CdsPrefetchFailureMode;
import ca.uhn.hapi.fhir.cdshooks.api.CdsService;
import ca.uhn.hapi.fhir.cdshooks.api.CdsServicePrefetch;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;

/**
 * CDS Hook service for the encounter-start hook.
 *
 * Triggered when a patient is admitted, arrives for outpatient visit,
 * or provider first engages with patient during an encounter.
 *
 * This is a SECONDARY hook per CRD spec:
 * - MAY return coverage-information but not required
 * - If coverage-info is returned, it SHALL NOT request clinical/administrative documentation
 */
public class EncounterStartService extends CdsServiceBase {

  @CdsService(
    value = "encounter-start-crd",
    hook = "encounter-start",
    title = "CRD Encounter Start Hook",
    description = "Coverage guidance when an encounter begins",
    extension = CRD_SERVICE_EXTENSION,
    extensionClass = CrdServiceExtension.class,
    allowAutoFhirClientPrefetch = true,
    prefetch = {
      @CdsServicePrefetch(value = "patient", query = "Patient/{{context.patientId}}"),
      @CdsServicePrefetch(value = "coverage", query = "Coverage?patient={{context.patientId}}&status=active"),
      @CdsServicePrefetch(value = "encounter", query = "Encounter/{{context.encounterId}}"),
      @CdsServicePrefetch(value = "user", query = "{{context.userId}}", failureMode = CdsPrefetchFailureMode.OMIT),
      @CdsServicePrefetch(value = "practitionerRoles", query = "PractitionerRole?practitioner={{context.userId}}", failureMode = CdsPrefetchFailureMode.OMIT),
      @CdsServicePrefetch(value = "conditions", query = "Condition?patient={{context.patientId}}&clinical-status=active", failureMode = CdsPrefetchFailureMode.OMIT)
    }
  )
  public CdsServiceResponseJson handleRequest(CdsServiceRequestJson request) {
    return processRequest(request);
  }

  @Override
  protected String getHookName() {
    return "encounter-start";
  }

  @Override
  protected void validateRequestInput(CdsServiceRequestJson request) {
    var context = request.getContext();
    String hook = getHookName();

    requireString(context, hook, "userId", true);
    requireString(context, hook, "patientId", true);
    requireString(context, hook, "encounterId", true);
  }

  @Override
  protected void validateExtractedResources(HookResourceContext context) {
    if (context.getEncounter() == null) {
      throw new CdsHooksException.BadRequestException(
        "encounterId context is required but was empty, missing, or could not be resolved.");
    }
  }

  @Override
  protected List<Resource> selectContextResources(HookResourceContext context) {
    return List.of(context.getEncounter());
  }
}
