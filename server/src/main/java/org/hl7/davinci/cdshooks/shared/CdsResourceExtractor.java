package org.hl7.davinci.cdshooks.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.MedicationDispense;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Procedure;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;

/**
 * Static utility for extracting CDS Hooks-specific resources from request context and prefetch.
 */
public final class CdsResourceExtractor {

  private static final Logger logger = LoggerFactory.getLogger(CdsResourceExtractor.class);

  private CdsResourceExtractor() {
  }

  /**
   * Extracts all resources from CDS Hook context and prefetch into a ResolvedResources container.
   */
  public static ResolvedResources extractAllResources(CdsServiceRequestJson request) {
    ResolvedResources context = new ResolvedResources();

    extractPatient(request, context);
    extractCoverage(request, context);
    extractEncounter(request, context);
    extractUser(request, context);
    extractOrders(request, context);
    extractSelections(request, context);
    extractDispatchedOrders(request, context);
    extractPerformer(request, context);
    extractAppointments(request, context);
    extractTasks(request, context);
    extractPrefetchResources(request, context);

    return context;
  }

  private static void extractPatient(CdsServiceRequestJson request, ResolvedResources context) {
    Object patientPrefetch = request.getPrefetch("patient");
    Patient patient = patientPrefetch instanceof Patient p ? p : null;
    if (patient == null && request.getContext().get("patientId") instanceof String patientId) {
      String reference = "Patient/" + patientId;
      patient = ResourceResolver.resolveReference(new Reference(reference), Patient.class, null, request);
    }
    context.setPatient(patient);
  }

  private static void extractCoverage(CdsServiceRequestJson request, ResolvedResources context) {
    // Per CRD IG: clients SHALL send only the primary coverage in prefetch
    Object coveragePrefetch = getPrefetchFlexible(request, "coverage");
    if (coveragePrefetch instanceof Bundle coverageBundle) {
      List<Coverage> coverages = ResourceResolver.extractFromBundle(coverageBundle, Coverage.class);
      context.setCoverageCount(coverages.size());

      if (!coverages.isEmpty()) {
        Coverage coverage = coverages.get(0);
        context.setCoverage(coverage);

        for (Reference payorRef : coverage.getPayor()) {
          Organization org = ResourceResolver.resolveReference(payorRef, Organization.class, coverage, request);
          if (org != null) {
            context.addOrganization(org);
          }
        }
      }
    } else if (coveragePrefetch instanceof Coverage coverage) {
      context.setCoverageCount(1);
      context.setCoverage(coverage);

      for (Reference payorRef : coverage.getPayor()) {
        Organization org = ResourceResolver.resolveReference(payorRef, Organization.class, coverage, request);
        if (org != null) {
          context.addOrganization(org);
        }
      }
    }
  }

  private static void extractEncounter(CdsServiceRequestJson request, ResolvedResources context) {
    Object encounterPrefetch = request.getPrefetch("encounter");
    if (encounterPrefetch instanceof Encounter encounter) {
      context.setEncounter(encounter);
    } else if (request.getContext().get("encounterId") instanceof String encounterId) {
      String encounterIdPart = ResourceResolver.normalizeReferenceId(encounterId, "Encounter");
      Encounter encounter = ResourceResolver.resolveFromServer(encounterIdPart, Encounter.class, request);
      context.setEncounter(encounter);
    }
  }

  private static void extractUser(CdsServiceRequestJson request, ResolvedResources context) {
    Object userPrefetch = request.getPrefetch("user");
    if (userPrefetch instanceof Practitioner practitioner) {
      context.addPractitioner(practitioner);
    } else if (userPrefetch instanceof PractitionerRole practitionerRole) {
      context.addPractitionerRole(practitionerRole);
    } else if (request.getContext().get("userId") instanceof String userId) {
      String userIdPart = ResourceResolver.normalizeReferenceId(userId, "Practitioner", "PractitionerRole");
      Practitioner practitioner = ResourceResolver.resolveFromServer(userIdPart, Practitioner.class, request);
      if (practitioner != null) {
        context.addPractitioner(practitioner);
      } else {
        PractitionerRole role = ResourceResolver.resolveFromServer(userIdPart, PractitionerRole.class, request);
        if (role != null) {
          context.addPractitionerRole(role);
        }
      }
    }
  }

  private static void extractOrders(CdsServiceRequestJson request, ResolvedResources context) {
    Object draftOrdersContext = request.getContext().get("draftOrders");
    if (draftOrdersContext instanceof Bundle draftOrders) {
      context.setOrders(ResourceResolver.extractOrders(draftOrders));
    } else if (draftOrdersContext instanceof Map<?, ?> mapEntry) {
      Resource resource = parseResourceFromMap(mapEntry);
      if (resource instanceof Bundle bundle) {
        context.setOrders(ResourceResolver.extractOrders(bundle));
      }
    }
  }

  private static void extractSelections(CdsServiceRequestJson request, ResolvedResources context) {
    Object selectionsContext = request.getContext().get("selections");
    if (selectionsContext instanceof List<?> selections) {
      context.setSelections(selections.stream().map(Object::toString).toList());
    }
  }

  private static void extractDispatchedOrders(CdsServiceRequestJson request, ResolvedResources context) {
    Object dispatchedOrdersContext = request.getContext().get("dispatchedOrders");
    if (dispatchedOrdersContext instanceof List<?> dispatchedOrders) {
      List<Resource> extracted = resolveOrderReferences(dispatchedOrders, request);
      context.setOrders(extracted);
    }
  }

  private static void extractPerformer(CdsServiceRequestJson request, ResolvedResources context) {
    Object performerContext = request.getContext().get("performer");
    if (performerContext instanceof String performer) {
      Reference ref = new Reference(performer);
      if (performer.contains("Practitioner/") && !performer.contains("PractitionerRole/")) {
        Practitioner practitioner = ResourceResolver.resolveReference(ref, Practitioner.class, null, request);
        if (practitioner != null) {
          context.addPractitioner(practitioner);
        }
      } else if (performer.contains("PractitionerRole/")) {
        PractitionerRole role = ResourceResolver.resolveReference(ref, PractitionerRole.class, null, request);
        if (role != null) {
          context.addPractitionerRole(role);
        }
      } else if (performer.contains("Organization/")) {
        Organization org = ResourceResolver.resolveReference(ref, Organization.class, null, request);
        if (org != null) {
          context.addOrganization(org);
        }
      } else if (performer.contains("CareTeam/")) {
        CareTeam careTeam = ResourceResolver.resolveReference(ref, CareTeam.class, null, request);
        if (careTeam != null) {
          context.addCareTeam(careTeam);
        }
      } else if (performer.contains("Location/")) {
        Location location = ResourceResolver.resolveReference(ref, Location.class, null, request);
        if (location != null) {
          context.addLocation(location);
        }
      }
    }
  }

  private static void extractAppointments(CdsServiceRequestJson request, ResolvedResources context) {
    Object appointmentsContext = request.getContext().get("appointments");
    if (appointmentsContext instanceof Bundle appointmentsBundle) {
      context.setAppointments(ResourceResolver.extractFromBundle(appointmentsBundle, Appointment.class));
    }
  }

  private static void extractTasks(CdsServiceRequestJson request, ResolvedResources context) {
    Object taskContext = request.getContext().get("task");
    if (taskContext instanceof Task task) {
      context.addTask(task);
    }

    Object fulfillmentTasksContext = request.getContext().get("fulfillmentTasks");
    if (fulfillmentTasksContext instanceof List<?> tasks) {
      for (Object entry : tasks) {
        if (entry instanceof Task task) {
          context.addTask(task);
        } else if (entry instanceof Map<?, ?> mapEntry) {
          Resource resource = parseResourceFromMap(mapEntry);
          if (resource instanceof Task task) {
            context.addTask(task);
          }
        }
      }
    } else if (fulfillmentTasksContext instanceof Task task) {
      context.addTask(task);
    }
  }

  private static void extractPrefetchResources(CdsServiceRequestJson request, ResolvedResources context) {
    // Practitioner and PractitionerRole (often provided separately from "user")
    Object practitionerPrefetch = getPrefetchFlexible(request, "practitioner");
    if (practitionerPrefetch instanceof Practitioner practitioner) {
      context.addPractitioner(practitioner);
    } else if (practitionerPrefetch instanceof Bundle practitionerBundle) {
      List<Practitioner> practitioners = ResourceResolver.extractFromBundle(practitionerBundle, Practitioner.class);
      if (!practitioners.isEmpty()) {
        context.getPractitioners().addAll(practitioners);
      }
    }

    Object practitionerRolesPrefetch = getPrefetchFlexible(request, "practitionerRoles");
    if (practitionerRolesPrefetch instanceof PractitionerRole practitionerRole) {
      context.addPractitionerRole(practitionerRole);
    } else if (practitionerRolesPrefetch instanceof Bundle rolesBundle) {
      List<PractitionerRole> roles = ResourceResolver.extractFromBundle(rolesBundle, PractitionerRole.class);
      if (!roles.isEmpty()) {
        context.getPractitionerRoles().addAll(roles);
      }
    }

    // Device history
    Object deviceHistoryPrefetch = getPrefetchFlexible(request, "deviceHistory");
    if (deviceHistoryPrefetch instanceof Bundle deviceHistoryBundle) {
      List<DeviceRequest> deviceRequests = ResourceResolver.extractFromBundle(deviceHistoryBundle, DeviceRequest.class);
      if (!deviceRequests.isEmpty()) {
        context.getDeviceHistory().addAll(deviceRequests);
      }
    }

    // Medications
    Object medicationsPrefetch = getPrefetchFlexible(request, "medications");
    if (medicationsPrefetch instanceof Bundle medicationsBundle) {
      context.setMedicationStatements(ResourceResolver.extractFromBundle(medicationsBundle, MedicationStatement.class));
    }

    // Medication history
    Object medicationHistoryPrefetch = getPrefetchFlexible(request, "medicationHistory");
    if (medicationHistoryPrefetch instanceof Bundle medicationHistoryBundle) {
      context.setMedicationHistory(ResourceResolver.extractFromBundle(medicationHistoryBundle, MedicationRequest.class));
    }

    // Medication dispenses
    Object medicationDispensePrefetch = getPrefetchFlexible(request, "medicationDispense");
    if (medicationDispensePrefetch instanceof Bundle medicationDispenseBundle) {
      context.setMedicationDispenses(
          ResourceResolver.extractFromBundle(medicationDispenseBundle, MedicationDispense.class));
    }

    // Procedures
    Object proceduresPrefetch = getPrefetchFlexible(request, "procedures");
    if (proceduresPrefetch instanceof Bundle proceduresBundle) {
      context.setProcedures(ResourceResolver.extractFromBundle(proceduresBundle, Procedure.class));
    }

    // Service requests
    Object serviceRequestsPrefetch = getPrefetchFlexible(request, "serviceRequests");
    if (serviceRequestsPrefetch instanceof Bundle serviceRequestsBundle) {
      context.setServiceRequests(ResourceResolver.extractFromBundle(serviceRequestsBundle, ServiceRequest.class));
    }

    Object serviceHistoryPrefetch = getPrefetchFlexible(request, "serviceHistory");
    if (serviceHistoryPrefetch instanceof Bundle serviceHistoryBundle) {
      List<ServiceRequest> serviceRequests = ResourceResolver.extractFromBundle(serviceHistoryBundle, ServiceRequest.class);
      if (!serviceRequests.isEmpty()) {
        context.getServiceRequests().addAll(serviceRequests);
      }
    }

    // Conditions
    Object conditionsPrefetch = getPrefetchFlexible(request, "conditions");
    if (conditionsPrefetch instanceof Bundle conditionsBundle) {
      context.setConditions(ResourceResolver.extractFromBundle(conditionsBundle, Condition.class));
    }
  }

  /**
   * Helper to get prefetch resource with flexible key.
   * Attempts to get a prefetch resource with the given key.
   * If not found, tries with "Bundle" suffix.
   */
  private static Object getPrefetchFlexible(CdsServiceRequestJson request, String key) {
    Object value = request.getPrefetch(key);
    if (value == null) {
      value = request.getPrefetch(key + "Bundle");
    }
    return value;
  }

  private static List<Resource> resolveOrderReferences(List<?> references, CdsServiceRequestJson request) {
    List<Resource> orders = new ArrayList<>();
    for (Object entry : references) {
      if (entry instanceof Resource resource) {
        if (ResourceResolver.isOrderResource(resource)) {
          orders.add(resource);
        }
      } else if (entry instanceof Map<?, ?> mapEntry) {
        Resource resource = parseResourceFromMap(mapEntry);
        if (resource != null && ResourceResolver.isOrderResource(resource)) {
          orders.add(resource);
        }
    } else if (entry instanceof Reference ref) {
      Resource resolved = ResourceResolver.resolveOrderReference(ref.getReference(), request);
      if (resolved != null) {
        orders.add(resolved);
      }
    } else if (entry instanceof String ref) {
      Resource resolved = ResourceResolver.resolveOrderReference(ref, request);
      if (resolved != null) {
        orders.add(resolved);
      }
    }
    }
    return orders;
  }


  private static Resource parseResourceFromMap(Map<?, ?> mapEntry) {
    if (mapEntry == null || !mapEntry.containsKey("resourceType")) {
      return null;
    }

    try {
      String json = new ObjectMapper().writeValueAsString(mapEntry);
      IBaseResource resource = FhirContext.forR4Cached().newJsonParser().parseResource(json);
      if (resource instanceof Resource castResource) {
        return castResource;
      }
    } catch (Exception e) {
      logger.warn("Failed to parse resource from context entry", e);
    }

    return null;
  }

}
