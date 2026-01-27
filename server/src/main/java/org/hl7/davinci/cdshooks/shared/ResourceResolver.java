package org.hl7.davinci.cdshooks.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.NutritionOrder;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Procedure;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.VisionPrescription;
import org.hl7.fhir.r4.model.MedicationDispense;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestAuthorizationJson;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor;

/**
 * Utility class for resolving FHIR resources from various sources.
 */
public class ResourceResolver {

  private static final Logger logger = LoggerFactory.getLogger(ResourceResolver.class);

  /**
   * Resolves a resource reference using all available strategies in this order:
   * - Contained resources
   * - Prefetch resources (direct or in bundles)
   * - FHIR server lookup
   */
  public static <T extends IBaseResource> T resolveReference(Reference ref, Class<T> resourceType,
      DomainResource parentResource, CdsServiceRequestJson request) {

    if (ref == null || !ref.hasReference()) {
      return null;
    }

    String reference = ref.getReference();

    // Check contained resources
    if (reference.startsWith("#") && parentResource != null) {
      T resource = findInContained(reference.substring(1), resourceType, parentResource);
      if (resource != null) {
        return resource;
      }
    }

    // Check prefetch bundles and direct prefetch
    T resource = findInPrefetch(reference, resourceType, request);
    if (resource != null) {
      return resource;
    }

    // Try server lookup
    resource = resolveFromServer(ref.getReferenceElement().getIdPart(), resourceType, request);
    if (resource != null) {
      return resource;
    }

    logger.warn("Could not resolve {} reference: {}", resourceType.getSimpleName(), reference);
    return null;
  }

  /**
   * Finds a resource in the parent's contained resources.
   */
  public static <T extends IBaseResource> T findInContained(String containedId, Class<T> resourceType,
      DomainResource parentResource) {

    for (Resource contained : parentResource.getContained()) {
      if (resourceType.isInstance(contained) && containedId.equals(contained.getIdElement().getIdPart())) {
        return resourceType.cast(contained);
      }
    }
    return null;
  }

  /**
   * Finds a resource in prefetch data (direct resources or bundles).
   */
  public static <T extends IBaseResource> T findInPrefetch(String reference, Class<T> resourceType,
      CdsServiceRequestJson request) {

    // Check all prefetch keys
    for (String key : request.getPrefetchKeys()) {
      Object prefetch = request.getPrefetch(key);
      logger.info("Checking prefetch key '{}' for reference '{}'", key, reference);

      // Direct resource match
      if (resourceType.isInstance(prefetch)) {
        T resource = resourceType.cast(prefetch);
        if (matchesReference(resource, reference)) {
          return resource;
        }
      }

      // Search within bundles
      if (prefetch instanceof Bundle bundle) {
        T resource = findInBundle(reference, resourceType, bundle);
        if (resource != null) {
          return resource;
        }
      }
    }

    return null;
  }

  /**
   * Finds a resource in a bundle.
   */
  public static <T extends IBaseResource> T findInBundle(String reference, Class<T> resourceType, Bundle bundle) {
    if (bundle == null || !bundle.hasEntry()) {
      return null;
    }

    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      if (resourceType.isInstance(entry.getResource())) {
        T resource = resourceType.cast(entry.getResource());
        if (matchesReference(resource, reference) ||
            (entry.hasFullUrl() && reference.equals(entry.getFullUrl()))) {
          return resource;
        }
      }
    }
    return null;
  }

  /**
   * Checks if a resource matches a reference string.
   */
  public static boolean matchesReference(IBaseResource resource, String reference) {
    if (resource.getIdElement() == null || resource.getIdElement().getIdPart() == null) {
      return false;
    }
    String resourceRef = resource.fhirType() + "/" + resource.getIdElement().getIdPart();
    return referencesMatch(reference, resourceRef);
  }

  /**
   * Compares two FHIR references for equality by parsing and comparing resource
   * type and ID.
   */
  public static boolean referencesMatch(String reference1, String reference2) {
    if (reference1 == null || reference2 == null || reference1.isEmpty() || reference2.isEmpty()) {
      return false;
    }
    if (reference1.equals(reference2)) {
      return true;
    }
    IdType id1 = new IdType(reference1);
    IdType id2 = new IdType(reference2);
    String resourceType1 = id1.getResourceType();
    String resourceType2 = id2.getResourceType();
    String idPart1 = id1.getIdPart();
    String idPart2 = id2.getIdPart();
    if (resourceType1 == null || resourceType2 == null || idPart1 == null || idPart2 == null) {
      return false;
    }
    return resourceType1.equals(resourceType2) && idPart1.equals(idPart2);
  }

  /**
   * Resolves a resource from the FHIR server.
   */
  public static <T extends IBaseResource> T resolveFromServer(String resourceId, Class<T> resourceType,
      CdsServiceRequestJson request) {

    try {
      String fhirServerBase = request.getFhirServer();
      if (fhirServerBase == null) {
        return null;
      }

      IGenericClient client = FhirContext.forR4Cached().newRestfulGenericClient(fhirServerBase);

      CdsServiceRequestAuthorizationJson authorization = request.getServiceRequestAuthorizationJson();
      if (authorization != null && authorization.getAccessToken() != null) {
        client.registerInterceptor(new BearerTokenAuthInterceptor(authorization.getAccessToken()));
      }

      IBaseResource resource = client.read().resource(resourceType).withId(resourceId).execute();

      if (resourceType.isInstance(resource)) {
        return resourceType.cast(resource);
      }
    } catch (Exception e) {
      logger.debug("Could not resolve {} {} from server: {}", resourceType.getSimpleName(), resourceId,
          e.getMessage());
    }
    return null;
  }

  /**
   * Extracts all resources from a bundle matching the given type.
   * Normalizes resource ids by stripping urn:uuid: prefix if present.
   */
  public static <T extends IBaseResource> List<T> extractFromBundle(Bundle bundle, Class<T> resourceType) {
    List<T> resources = new ArrayList<>();
    if (bundle != null && bundle.hasEntry()) {
      for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
        if (resourceType.isInstance(entry.getResource())) {
          T resource = resourceType.cast(entry.getResource());
          // normalizeResourceId(resource, entry.getFullUrl());
          resources.add(resource);
        }
      }
    }
    return resources;
  }

  /**
   * Strips urn:uuid: prefix from an ID string if present.
   */
  public static String normalizeId(String id) {
    if (id != null && id.startsWith("urn:uuid:")) {
      return id.substring("urn:uuid:".length());
    }
    return id;
  }

  /**
   * Normalizes a resource id by handling urn:uuid: prefixes from Bundle fullUrls.
   * When HAPI parses Bundle entries with urn:uuid fullUrls, it may set the
   * resource
   * id to the full urn:uuid value, which is not a valid FHIR id format.
   */
  private static void normalizeResourceId(IBaseResource resource, String fullUrl) {
    if (!(resource instanceof Resource r)) {
      return;
    }

    String currentId = r.hasIdElement() ? r.getIdElement().getIdPart() : null;
    if (currentId == null) {
      return;
    }

    String normalizedId = normalizeId(currentId);
    if (!normalizedId.equals(currentId)) {
      r.setId(normalizedId);
      logger.debug("Normalized resource id from {} to {}", currentId, normalizedId);
    }
  }

  /**
   * Checks if a resource is a type that can be in an order bundle.
   * 
   * @see <a href=
   *      "https://build.fhir.org/ig/HL7/davinci-crd/en/StructureDefinition-profile-bundle-request.html">CRD
   *      Bundle of Request Resources</a>
   */
  public static boolean isOrderResource(Resource resource) {
    return resource instanceof Appointment ||
        resource instanceof CommunicationRequest ||
        resource instanceof DeviceRequest ||
        resource instanceof Encounter ||
        resource instanceof MedicationRequest ||
        resource instanceof NutritionOrder ||
        resource instanceof ServiceRequest ||
        resource instanceof VisionPrescription;
  }

  /**
   * Extracts all order resources from a bundle.
   */
  public static List<Resource> extractOrders(Bundle bundle) {
    List<Resource> orders = new ArrayList<>();
    if (bundle != null && bundle.hasEntry()) {
      for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
        if (isOrderResource(entry.getResource())) {
          orders.add(entry.getResource());
        }
      }
    }
    return orders;
  }

  private static List<Resource> resolveOrderReferences(List<?> references, CdsServiceRequestJson request) {
    List<Resource> orders = new ArrayList<>();
    for (Object entry : references) {
      if (entry instanceof Resource resource) {
        if (isOrderResource(resource)) {
          orders.add(resource);
        }
      } else if (entry instanceof Map<?, ?> mapEntry) {
        Resource resource = parseResourceFromMap(mapEntry);
        if (resource != null && isOrderResource(resource)) {
          orders.add(resource);
        }
      } else if (entry instanceof Reference ref) {
        Resource resolved = resolveOrderReference(ref.getReference(), request);
        if (resolved != null) {
          orders.add(resolved);
        }
      } else if (entry instanceof String ref) {
        Resource resolved = resolveOrderReference(ref, request);
        if (resolved != null) {
          orders.add(resolved);
        }
      }
    }
    return orders;
  }

  private static Resource resolveOrderReference(String reference, CdsServiceRequestJson request) {
    if (reference == null || reference.isBlank()) {
      return null;
    }

    Reference ref = new Reference(reference);
    String resourceType = ref.getReferenceElement().getResourceType();
    if (resourceType == null) {
      IdType idType = new IdType(reference);
      resourceType = idType.getResourceType();
    }
    if (resourceType == null) {
      return null;
    }

    return switch (resourceType) {
      case "CommunicationRequest" -> resolveReference(ref, CommunicationRequest.class, null, request);
      case "DeviceRequest" -> resolveReference(ref, DeviceRequest.class, null, request);
      case "MedicationRequest" -> resolveReference(ref, MedicationRequest.class, null, request);
      case "NutritionOrder" -> resolveReference(ref, NutritionOrder.class, null, request);
      case "ServiceRequest" -> resolveReference(ref, ServiceRequest.class, null, request);
      case "VisionPrescription" -> resolveReference(ref, VisionPrescription.class, null, request);
      case "Appointment" -> resolveReference(ref, Appointment.class, null, request);
      case "Encounter" -> resolveReference(ref, Encounter.class, null, request);
      default -> null;
    };
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

  /**
   * Helper to get prefetch resource with flexible key.
   * Attempts to get a prefetch resource with the given key.
   * If not found, tries with "Bundle" suffix.
   * (e.g., if no "coverage" then check "coverageBundle")
   */
  private static Object getPrefetchFlexible(CdsServiceRequestJson request, String key) {
    Object value = request.getPrefetch(key);
    if (value == null) {
      value = request.getPrefetch(key + "Bundle");
    }
    return value;
  }

  /**
   * Extracts all resources from CDS Hook context and prefetch into a
   * HookResourceContext.
   */
  public static HookResourceContext extractAllResources(CdsServiceRequestJson request) {
    HookResourceContext context = new HookResourceContext();

    // Extract patient
    Object patientPrefetch = request.getPrefetch("patient");
    Patient patient = patientPrefetch instanceof Patient p ? p : null;
    if (patient == null && request.getContext().get("patientId") instanceof String patientId) {
      String reference = "Patient/" + patientId;
      patient = resolveReference(new Reference(reference), Patient.class, null, request);
    }
    context.setPatient(patient);

    // Extract coverage
    // Per CRD IG: clients SHALL send only the primary coverage in prefetch so we
    // only use the first coverage
    Object coveragePrefetch = getPrefetchFlexible(request, "coverage");
    if (coveragePrefetch instanceof Bundle coverageBundle) {
      List<Coverage> coverages = extractFromBundle(coverageBundle, Coverage.class);
      context.setCoverageCount(coverages.size());

      if (!coverages.isEmpty()) {
        Coverage coverage = coverages.get(0);
        context.setCoverage(coverage);

        // Extract payor organizations from coverage
        for (Reference payorRef : coverage.getPayor()) {
          Organization org = resolveReference(payorRef, Organization.class, coverage, request);
          if (org != null) {
            context.addOrganization(org);
          }
        }
      }
    } else if (coveragePrefetch instanceof Coverage coverage) {
      context.setCoverageCount(1);
      context.setCoverage(coverage);

      for (Reference payorRef : coverage.getPayor()) {
        Organization org = resolveReference(payorRef, Organization.class, coverage, request);
        if (org != null) {
          context.addOrganization(org);
        }
      }
    }

    // Extract encounter
    Object encounterPrefetch = request.getPrefetch("encounter");
    if (encounterPrefetch instanceof Encounter encounter) {
      context.setEncounter(encounter);
    } else if (request.getContext().get("encounterId") instanceof String encounterId) {
      Encounter encounter = resolveFromServer(encounterId, Encounter.class, request);
      context.setEncounter(encounter);
    }

    // Extract user (Practitioner or PractitionerRole)
    Object userPrefetch = request.getPrefetch("user");
    if (userPrefetch instanceof Practitioner practitioner) {
      context.addPractitioner(practitioner);
    } else if (userPrefetch instanceof PractitionerRole practitionerRole) {
      context.addPractitionerRole(practitionerRole);
    } else if (request.getContext().get("userId") instanceof String userId) {
      // Try Practitioner first, then PractitionerRole
      Practitioner practitioner = resolveFromServer(userId, Practitioner.class, request);
      if (practitioner != null) {
        context.addPractitioner(practitioner);
      } else {
        PractitionerRole role = resolveFromServer(userId, PractitionerRole.class, request);
        if (role != null) {
          context.addPractitionerRole(role);
        }
      }
    }

    // Extract orders from draftOrders context
    Object draftOrdersContext = request.getContext().get("draftOrders");
    if (draftOrdersContext instanceof Bundle draftOrders) {
      context.setOrders(extractOrders(draftOrders));
    }

    // Extract orders from dispatchedOrders context (for order-dispatch hook)
    Object dispatchedOrdersContext = request.getContext().get("dispatchedOrders");
    if (dispatchedOrdersContext == null) {
      dispatchedOrdersContext = request.getContext().get("dispatched-orders");
    }
    if (dispatchedOrdersContext instanceof List<?> dispatchedOrders) {
      List<Resource> extracted = resolveOrderReferences(dispatchedOrders, request);
      context.setOrders(extracted);
    }

    // Extract performer from context (for order-dispatch hook)
    Object performerContext = request.getContext().get("performer");
    if (performerContext instanceof String performer) {
      Reference ref = new Reference(performer);
      if (performer.contains("Practitioner/") && !performer.contains("PractitionerRole/")) {
        Practitioner practitioner = resolveReference(ref, Practitioner.class, null, request);
        if (practitioner != null) {
          context.addPractitioner(practitioner);
        }
      } else if (performer.contains("PractitionerRole/")) {
        PractitionerRole role = resolveReference(ref, PractitionerRole.class, null, request);
        if (role != null) {
          context.addPractitionerRole(role);
        }
      } else if (performer.contains("Organization/")) {
        Organization org = resolveReference(ref, Organization.class, null, request);
        if (org != null) {
          context.addOrganization(org);
        }
      } else if (performer.contains("CareTeam/")) {
        CareTeam careTeam = resolveReference(ref, CareTeam.class, null, request);
        if (careTeam != null) {
          context.addCareTeam(careTeam);
        }
      } else if (performer.contains("Location/")) {
        Location location = resolveReference(ref, Location.class, null, request);
        if (location != null) {
          context.addLocation(location);
        }
      }
    }

    // Extract appointments
    Object appointmentsContext = request.getContext().get("appointments");
    if (appointmentsContext instanceof Bundle appointmentsBundle) {
      context.setAppointments(extractFromBundle(appointmentsBundle, Appointment.class));
    }

    // Extract task
    Object taskContext = request.getContext().get("task");
    if (taskContext instanceof Task task) {
      context.setTask(task);
    }

    // Extract fulfillment tasks
    Object fulfillmentTasksContext = request.getContext().get("fulfillmentTasks");
    if (fulfillmentTasksContext == null) {
      fulfillmentTasksContext = request.getContext().get("fulfillment-tasks");
    }
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

    // Extract medications
    Object medicationsPrefetch = getPrefetchFlexible(request, "medications");
    if (medicationsPrefetch instanceof Bundle medicationsBundle) {
      context.setMedicationStatements(extractFromBundle(medicationsBundle, MedicationStatement.class));
    }

    // Extract medication history
    Object medicationHistoryPrefetch = getPrefetchFlexible(request, "medicationHistory");
    if (medicationHistoryPrefetch instanceof Bundle medicationHistoryBundle) {
      context.setMedicationHistory(extractFromBundle(medicationHistoryBundle, MedicationRequest.class));
    }

    // Extract medication dispenses
    Object medicationDispensePrefetch = getPrefetchFlexible(request, "medicationDispense");
    if (medicationDispensePrefetch instanceof Bundle medicationDispenseBundle) {
      context.setMedicationDispenses(extractFromBundle(medicationDispenseBundle, MedicationDispense.class));
    }

    // Extract procedures
    Object proceduresPrefetch = getPrefetchFlexible(request, "procedures");
    if (proceduresPrefetch instanceof Bundle proceduresBundle) {
      context.setProcedures(extractFromBundle(proceduresBundle, Procedure.class));
    }

    // Extract service requests
    Object serviceRequestsPrefetch = getPrefetchFlexible(request, "serviceRequests");
    if (serviceRequestsPrefetch instanceof Bundle serviceRequestsBundle) {
      context.setServiceRequests(extractFromBundle(serviceRequestsBundle, ServiceRequest.class));
    }

    return context;
  }
}
