package org.hl7.davinci.cdshooks.shared;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.NutritionOrder;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Task;
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
    String resourceRef = resource.fhirType() + "/" + resource.getIdElement().getIdPart();
    return reference.equals(resourceRef) || reference.endsWith(resourceRef);
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
   */
  public static <T extends IBaseResource> List<T> extractFromBundle(Bundle bundle, Class<T> resourceType) {
    List<T> resources = new ArrayList<>();
    if (bundle != null && bundle.hasEntry()) {
      for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
        if (resourceType.isInstance(entry.getResource())) {
          resources.add(resourceType.cast(entry.getResource()));
        }
      }
    }
    return resources;
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
        resource instanceof ServiceRequest;
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

  /**
   * Extracts all resources from CDS Hook context and prefetch into a
   * HookResourceContext.
   */
  public static HookResourceContext extractAllResources(CdsServiceRequestJson request) {
    HookResourceContext context = new HookResourceContext();

    // Extract patient
    Patient patient = (Patient) request.getPrefetch("patient");
    if (patient == null && request.getContext().get("patientId") != null) {
      String patientId = (String) request.getContext().get("patientId");
      // patient = resolveFromServer(patientId, Patient.class, request);
      String reference = "Patient/" + patientId;
      patient = resolveReference(new Reference(reference), Patient.class, null, request);
    }
    context.setPatient(patient);

    // Extract coverage
    // Per CRD IG: clients SHALL send only the primary coverage in prefetch so we
    // only use the first coverage
    Bundle coverageBundle = (Bundle) request.getPrefetch("coverage");
    if (coverageBundle != null) {
      List<Coverage> coverages = extractFromBundle(coverageBundle, Coverage.class);

      if (!coverages.isEmpty()) {
        // Warn if client sent multiple coverages (should only send primary)
        if (coverages.size() > 1) {
          logger.warn(
              "Received {} Coverage resources in prefetch, but CRD clients should only send the primary coverage. Using first coverage.",
              coverages.size());
        }

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
    }

    // Extract encounter
    Object encounterPrefetch = request.getPrefetch("encounter");
    if (encounterPrefetch instanceof Encounter encounter) {
      context.setEncounter(encounter);
    } else if (request.getContext().get("encounterId") != null) {
      String encounterId = (String) request.getContext().get("encounterId");
      Encounter encounter = resolveFromServer(encounterId, Encounter.class, request);
      context.setEncounter(encounter);
    }

    // Extract user (Practitioner or PractitionerRole)
    Object userPrefetch = request.getPrefetch("user");
    if (userPrefetch instanceof Practitioner practitioner) {
      context.addPractitioner(practitioner);
    } else if (userPrefetch instanceof PractitionerRole practitionerRole) {
      context.addPractitionerRole(practitionerRole);
    } else if (request.getContext().get("userId") != null) {
      String userId = (String) request.getContext().get("userId");
      // Try Practitioner first, then PractitionerRole
      Practitioner practitioner = resolveFromServer(userId, Practitioner.class, request);
      if (practitioner != null) {
        context.addPractitioner(practitioner);
      } else {
        PractitionerRole practitionerRole = resolveFromServer(userId, PractitionerRole.class, request);
        if (practitionerRole != null) {
          context.addPractitionerRole(practitionerRole);
        }
      }
    }

    // Extract performer (from order-dispatch)
    Object performerPrefetch = request.getPrefetch("performer");
    if (performerPrefetch instanceof Practitioner practitioner) {
      context.addPractitioner(practitioner);
    } else if (performerPrefetch instanceof Organization organization) {
      context.addOrganization(organization);
    }

    // Extract orders from draftOrders context
    Bundle draftOrders = (Bundle) request.getContext().get("draftOrders");
    if (draftOrders != null) {
      context.setOrders(extractOrders(draftOrders));
    }

    // Extract appointments
    Object appointmentsContext = request.getContext().get("appointments");
    if (appointmentsContext instanceof Bundle appointmentsBundle) {
      context.setAppointments(extractFromBundle(appointmentsBundle, Appointment.class));
    }

    // Extract task (from order-dispatch)
    Task task = (Task) request.getContext().get("task");
    context.setTask(task);

    return context;
  }
}
