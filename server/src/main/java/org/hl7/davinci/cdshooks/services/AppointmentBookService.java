package org.hl7.davinci.cdshooks.services;

import java.util.ArrayList;
import java.util.List;

import org.hl7.davinci.cdshooks.error.CdsHooksException;
import org.hl7.davinci.cdshooks.shared.CdsServiceBase;
import org.hl7.davinci.cdshooks.shared.CrdServiceExtension;
import org.hl7.davinci.cdshooks.shared.ResolvedResources;
import org.hl7.fhir.r4.model.Resource;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.hapi.fhir.cdshooks.api.CdsPrefetchFailureMode;
import ca.uhn.hapi.fhir.cdshooks.api.CdsService;
import ca.uhn.hapi.fhir.cdshooks.api.CdsServicePrefetch;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;

/**
 * CDS Hook service for the appointment-book hook.
 *
 * Triggered when a user books a future appointment for a patient.
 * This is a primary hook that requires coverage-information responses.
 */
public class AppointmentBookService extends CdsServiceBase {

  @CdsService(
    value = "appointment-book-crd",
    hook = "appointment-book",
    title = "CRD Appointment Book Hook",
    description = "Indicates coverage requirements when booking appointments for future services",
    extension = CRD_SERVICE_EXTENSION,
    extensionClass = CrdServiceExtension.class,
    allowAutoFhirClientPrefetch = true,
    prefetch = {
      @CdsServicePrefetch(value = "patient", query = "Patient/{{context.patientId}}"),
      @CdsServicePrefetch(value = "coverage", query = "Coverage?patient={{context.patientId}}&status=active"),
      @CdsServicePrefetch(value = "encounter", query = "Encounter/{{context.encounterId}}", failureMode = CdsPrefetchFailureMode.OMIT),
      @CdsServicePrefetch(value = "practitioner", query = "{{context.userId}}", failureMode = CdsPrefetchFailureMode.OMIT),
      @CdsServicePrefetch(value = "practitionerRoles", query = "PractitionerRole?practitioner={{context.userId}}", failureMode = CdsPrefetchFailureMode.OMIT),
      @CdsServicePrefetch(value = "procedures", query = "Procedure?patient={{context.patientId}}&status=completed", failureMode = CdsPrefetchFailureMode.OMIT),
      @CdsServicePrefetch(value = "serviceRequests", query = "ServiceRequest?patient={{context.patientId}}&status=active,completed", failureMode = CdsPrefetchFailureMode.OMIT),
      @CdsServicePrefetch(value = "questionnaireResponses", query = "QuestionnaireResponse?patient={{context.patientId}}&status=completed", failureMode = CdsPrefetchFailureMode.OMIT)
    }
  )
  public CdsServiceResponseJson handleRequest(CdsServiceRequestJson request) {
    return processRequest(request);
  }

  @Override
  protected String getHookName() {
    return "appointment-book";
  }

  @Override
  protected void validateRequestInput(CdsServiceRequestJson request) {
    var context = request.getContext();
    String hook = getHookName();

    requireString(context, hook, "userId", true);
    requireString(context, hook, "patientId", true);
    requireString(context, hook, "encounterId", false);
    requireObject(context, hook, "appointments", true);
  }

  @Override
  protected void validateExtractedResources(ResolvedResources context) {
    if (context.getAppointments().isEmpty()) {
      throw new CdsHooksException.BadRequestException(
        "appointments context is required but was empty, missing, or could not be resolved."
      );
    }
  }

  @Override
  protected List<Resource> selectContextResources(ResolvedResources context) {
    return new ArrayList<>(context.getAppointments());
  }
}
