package org.hl7.davinci.cdshooks.shared;

import java.util.ArrayList;
import java.util.List;

import org.hl7.davinci.common.FhirUtil;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.NutritionOrder;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.SupplyRequest;
import org.hl7.fhir.r4.model.VisionPrescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestAuthorizationJson;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor;

/**
 * Utility class for generic FHIR resource reference resolution.
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
   * @see FhirUtil#findInContained(String, Class, DomainResource)
   */
  public static <T extends IBaseResource> T findInContained(String containedId, Class<T> resourceType,
      DomainResource parentResource) {
    return FhirUtil.findInContained(containedId, resourceType, parentResource);
  }

  /**
   * Finds a resource in prefetch data (direct resources or bundles).
   */
  public static <T extends IBaseResource> T findInPrefetch(String reference, Class<T> resourceType,
      CdsServiceRequestJson request) {

    if (request == null || request.getPrefetchKeys() == null) {
      return null;
    }

    for (String key : request.getPrefetchKeys()) {
      Object prefetch = request.getPrefetch(key);
      logger.info("Checking prefetch key '{}' for reference '{}'", key, reference);

      // Direct resource match
      if (resourceType.isInstance(prefetch) && prefetch instanceof Resource res) {
        if (referencesMatchResource(reference, res)) {
          return resourceType.cast(prefetch);
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
        if (referencesMatchResource(reference, entry.getResource()) ||
            (entry.hasFullUrl() && reference.equals(entry.getFullUrl()))) {
          return resourceType.cast(entry.getResource());
        }
      }
    }
    return null;
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
   * Extracts the resource type from a reference string.
   */
  public static String getReferenceResourceType(String reference) {
    if (reference == null || reference.isBlank()) {
      return null;
    }
    return getReferenceResourceType(new Reference(reference));
  }

  /**
   * Extracts the resource type from a Reference object.
   */
  public static String getReferenceResourceType(Reference reference) {
    if (reference == null || !reference.hasReference()) {
      return null;
    }
    String resourceType = reference.getReferenceElement().getResourceType();
    if (resourceType != null) {
      return resourceType;
    }
    IdType idType = new IdType(reference.getReference());
    return idType.getResourceType();
  }

  /**
   * Normalizes a reference string to its ID part when the type matches.
   */
  public static String normalizeReferenceId(String reference, String... allowedTypes) {
    if (reference == null || reference.isBlank()) {
      return reference;
    }

    IdType idType = new IdType(reference);
    if (idType.getResourceType() == null || idType.getIdPart() == null) {
      return reference;
    }

    if (allowedTypes == null || allowedTypes.length == 0) {
      return idType.getIdPart();
    }

    for (String type : allowedTypes) {
      if (type.equals(idType.getResourceType())) {
        return idType.getIdPart();
      }
    }

    return reference;
  }

  /**
   * Resolves a resource from the FHIR server.
   */
  public static <T extends IBaseResource> T resolveFromServer(String resourceId, Class<T> resourceType,
      CdsServiceRequestJson request) {

    if (request == null) {
      return null;
    }

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
      logger.warn("Could not resolve {} {} from server: {}", resourceType.getSimpleName(), resourceId,
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
          T resource = resourceType.cast(entry.getResource());
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
   * Returns the URL with swapped protocol (https↔http), or null if not http/https.
   * @see FhirUtil#getAlternateProtocolUrl(String)
   */
  public static String getAlternateProtocolUrl(String url) {
    return FhirUtil.getAlternateProtocolUrl(url);
  }

  /**
   * Returns a normalized relative reference string ("Type/id") for a resource,
   * stripping any urn:uuid: prefix from the ID. Returns null if the resource
   * has no usable ID.
   */
  public static String toRelativeReference(Resource resource) {
    if (resource == null || !resource.hasIdElement()) {
      return null;
    }
    String idPart = normalizeId(resource.getIdElement().getIdPart());
    if (idPart == null || idPart.isBlank()) {
      return null;
    }
    return resource.fhirType() + "/" + idPart;
  }

  /**
   * Finds organizations from the list that match the coverage's payor references.
   */
  public static List<Organization> findPayorOrganizations(Coverage coverage, List<Organization> organizations) {
    List<Organization> matched = new ArrayList<>();
    if (coverage == null || organizations == null) {
      return matched;
    }
    for (Reference payorRef : coverage.getPayor()) {
      if (!payorRef.hasReference()) {
        continue;
      }
      for (Organization org : organizations) {
        String orgRef = toRelativeReference(org);
        if (orgRef != null && referencesMatch(payorRef.getReference(), orgRef)) {
          matched.add(org);
        }
      }
    }
    return matched;
  }

  /**
   * Resolves an order reference using the supported order resource types.
   */
  public static Resource resolveOrderReference(String reference, CdsServiceRequestJson request) {
    if (reference == null || reference.isBlank()) {
      return null;
    }

    Reference ref = new Reference(reference);
    String resourceType = getReferenceResourceType(ref);
    if (resourceType == null) {
      return null;
    }

    return switch (resourceType) {
      case "CommunicationRequest" -> resolveReference(ref, CommunicationRequest.class, null, request);
      case "DeviceRequest" -> resolveReference(ref, DeviceRequest.class, null, request);
      case "MedicationRequest" -> resolveReference(ref, MedicationRequest.class, null, request);
      case "NutritionOrder" -> resolveReference(ref, NutritionOrder.class, null, request);
      case "ServiceRequest" -> resolveReference(ref, ServiceRequest.class, null, request);
      case "SupplyRequest" -> resolveReference(ref, SupplyRequest.class, null, request);
      case "VisionPrescription" -> resolveReference(ref, VisionPrescription.class, null, request);
      case "Appointment" -> resolveReference(ref, Appointment.class, null, request);
      case "Encounter" -> resolveReference(ref, Encounter.class, null, request);
      default -> null;
    };
  }

  /**
   * Checks whether a reference points to the given resource, including urn:uuid
   * normalization.
   */
  public static boolean referencesMatchResource(String reference, Resource resource) {
    if (reference == null || resource == null || !resource.hasIdElement()) {
      return false;
    }

    String idPart = resource.getIdElement().getIdPart();
    if (idPart == null) {
      return false;
    }

    // Try raw ID first
    String rawRef = resource.fhirType() + "/" + idPart;
    if (referencesMatch(reference, rawRef)) {
      return true;
    }

    // Try with normalized ID (strips urn:uuid: prefix)
    String normalizedRef = toRelativeReference(resource);
    if (normalizedRef == null || normalizedRef.equals(rawRef)) {
      return false;
    }

    return referencesMatch(reference, normalizedRef);
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
        resource instanceof SupplyRequest ||
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
}
