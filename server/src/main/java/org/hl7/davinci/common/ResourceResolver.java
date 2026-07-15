package org.hl7.davinci.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestAuthorizationJson;
import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor;
import ca.uhn.fhir.util.BundleUtil;

/**
 * Utility class for generic FHIR resource reference resolution.
 */
public class ResourceResolver {

  private static final Logger logger = LoggerFactory.getLogger(ResourceResolver.class);
  private static final FhirContext R4_CTX = FhirContext.forR4Cached();

  /**
   * Resource types the payer is authoritative for and that are safe to fall back
   * to the local DAO when prefetch and the EHR's FHIR server fail to resolve a
   * reference. Restricted to payer-owned and definitional resources to avoid
   * silently substituting EHR-owned clinical data (Patient, Encounter, etc.) on
   * an ID collision.
   */
  private static final Set<String> PAYER_OWNED_TYPES = Set.of(
      "Organization",
      "PlanDefinition",
      "Library",
      "Questionnaire",
      "ValueSet",
      "CodeSystem",
      "StructureDefinition");

  /**
   * Resolves a resource reference using all available strategies in this order:
   * - Contained resources
   * - Prefetch resources (direct or in bundles)
   * - FHIR server lookup
   */
  public static <T extends IBaseResource> T resolveReference(Reference ref, Class<T> resourceType,
      DomainResource parentResource, CdsServiceRequestJson request) {
    return resolveReference(ref, resourceType, parentResource, request, null);
  }

  /**
   * Resolves a resource reference with an optional local-DAO fallback. The DAO is
   * only consulted as a last resort and only for {@link #PAYER_OWNED_TYPES} —
   * resource types the payer is authoritative for. Clinical resource types
   * deliberately do not fall back to the local DAO to avoid silently substituting
   * EHR-owned data on an ID collision.
   */
  public static <T extends IBaseResource> T resolveReference(Reference ref, Class<T> resourceType,
      DomainResource parentResource, CdsServiceRequestJson request, DaoRegistry daoRegistry) {

    if (ref == null || !ref.hasReference()) {
      return null;
    }

    String reference = ref.getReference();

    // Check contained resources
    if (reference.startsWith("#") && parentResource != null) {
      Resource contained = parentResource.getContained(reference);
      if (resourceType.isInstance(contained)) {
        return resourceType.cast(contained);
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

    // Local DAO fallback (allowlisted types only)
    if (daoRegistry != null && Resource.class.isAssignableFrom(resourceType)
        && PAYER_OWNED_TYPES.contains(resourceType.getSimpleName())) {
      @SuppressWarnings("unchecked")
      Class<? extends Resource> domainType = (Class<? extends Resource>) resourceType;
      Resource fromDao = resolveTypedReferenceFromDao(ref, domainType, parentResource, daoRegistry);
      if (resourceType.isInstance(fromDao)) {
        return resourceType.cast(fromDao);
      }
    }

    logger.warn("Could not resolve {} reference: {}", resourceType.getSimpleName(), reference);
    return null;
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
    if (reference == null || reference.isBlank() || bundle == null || !bundle.hasEntry()) {
      return null;
    }

    IBase resolved = BundleUtil.getReferenceInBundle(R4_CTX, reference, bundle);
    if (resourceType.isInstance(resolved)) {
      return resourceType.cast(resolved);
    }

    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      if (!resourceType.isInstance(entry.getResource())) {
        continue;
      }
      if (entry.hasFullUrl() && reference.equals(entry.getFullUrl())) {
        return resourceType.cast(entry.getResource());
      }
    }

    for (T resource : BundleUtil.toListOfResourcesOfType(R4_CTX, bundle, resourceType)) {
      if (resource instanceof Resource castResource && referencesMatchResource(reference, castResource)) {
        return resource;
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

    IdType id1;
    IdType id2;
    try {
      id1 = new IdType(reference1).toUnqualifiedVersionless();
      id2 = new IdType(reference2).toUnqualifiedVersionless();
    } catch (Exception e) {
      return false;
    }

    if (!id1.hasResourceType() || !id2.hasResourceType() || !id1.hasIdPart() || !id2.hasIdPart()) {
      return false;
    }

    return id1.equalsIgnoreBase(id2);
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

    IdType idType;
    try {
      idType = new IdType(reference);
    } catch (Exception e) {
      return reference;
    }

    if (!idType.hasResourceType() || !idType.hasIdPart()) {
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
   * Normalizes a typed reference to its versionless form (preserving base URL if present).
   * Returns null when the reference is missing, mismatched, or malformed.
   */
  public static String toVersionlessTypedReference(Reference reference, String expectedType) {
    if (reference == null || !reference.hasReference()) {
      return null;
    }
    return toVersionlessTypedReference(reference.getReference(), expectedType);
  }

  /**
   * Normalizes a typed reference string to its versionless form
   * (for example "http://x/Patient/123/_history/1" -> "http://x/Patient/123").
   * Returns null when the reference is missing, mismatched, or malformed.
   */
  public static String toVersionlessTypedReference(String reference, String expectedType) {
    if (reference == null || reference.isBlank() || expectedType == null || expectedType.isBlank()) {
      return null;
    }

    IdType idType;
    try {
      idType = new IdType(reference);
    } catch (Exception e) {
      return null;
    }

    if (!expectedType.equals(idType.getResourceType()) || !idType.hasIdPart()) {
      return null;
    }

    String versionless = idType.toVersionless().getValue();
    if (versionless != null && !versionless.isBlank()) {
      return versionless;
    }

    return expectedType + "/" + idType.getIdPart();
  }

  /**
   * Resolves a typed reference from inline/contained resources or the local DAO
   * registry.
   */
  public static <T extends Resource> T resolveTypedReferenceFromDao(
      Reference reference,
      Class<T> resourceType,
      DomainResource parentResource,
      DaoRegistry daoRegistry) {

    if (reference == null || resourceType == null) {
      return null;
    }

    if (resourceType.isInstance(reference.getResource())) {
      return resourceType.cast(reference.getResource());
    }

    if (!reference.hasReference()) {
      return null;
    }

    String ref = reference.getReference();
    if (ref == null || ref.isBlank()) {
      return null;
    }

    if (ref.startsWith("#") && parentResource != null) {
      Resource contained = parentResource.getContained(ref);
      if (resourceType.isInstance(contained)) {
        return resourceType.cast(contained);
      }
      return null;
    }

    if (parentResource != null) {
      for (Resource contained : parentResource.getContained()) {
        if (resourceType.isInstance(contained) && referencesMatchResource(ref, contained)) {
          return resourceType.cast(contained);
        }
      }
    }

    if (daoRegistry == null) {
      return null;
    }

    IIdType idType = reference.getReferenceElement();
    String resolvedType = idType.getResourceType();
    if (resolvedType == null || resolvedType.isBlank()) {
      resolvedType = resourceType.getSimpleName();
    }
    if (!resourceType.getSimpleName().equals(resolvedType)) {
      return null;
    }

    String idPart = normalizeId(idType.getIdPart());
    if (idPart == null || idPart.isBlank()) {
      return null;
    }

    try {
      IBaseResource resource = daoRegistry.getResourceDao(resourceType)
          .read(new IdType(resolvedType, idPart), new SystemRequestDetails());
      if (resourceType.isInstance(resource)) {
        return resourceType.cast(resource);
      }
    } catch (Exception e) {
      logger.debug("Could not resolve {} reference {} from DAO: {}",
          resourceType.getSimpleName(), ref, e.getMessage());
    }

    return null;
  }

  /**
   * Resolves an untyped reference from inline/contained resources or the local DAO
   * registry.
   */
  public static Resource resolveReferenceFromDao(
      Reference reference,
      DomainResource parentResource,
      DaoRegistry daoRegistry) {

    if (reference == null) {
      return null;
    }

    if (reference.getResource() instanceof Resource inline) {
      return inline;
    }

    if (!reference.hasReference()) {
      return null;
    }

    String ref = reference.getReference();
    if (ref == null || ref.isBlank()) {
      return null;
    }

    if (ref.startsWith("#") && parentResource != null) {
      return parentResource.getContained(ref);
    }

    if (parentResource != null) {
      for (Resource contained : parentResource.getContained()) {
        if (referencesMatchResource(ref, contained)) {
          return contained;
        }
      }
    }

    if (daoRegistry == null) {
      return null;
    }

    IIdType idType = reference.getReferenceElement();
    String resourceType = idType.getResourceType();
    if (resourceType == null || resourceType.isBlank()) {
      return null;
    }

    String idPart = normalizeId(idType.getIdPart());
    if (idPart == null || idPart.isBlank()) {
      return null;
    }

    try {
      IBaseResource resource = daoRegistry.getResourceDao(resourceType)
          .read(new IdType(resourceType, idPart), new SystemRequestDetails());
      if (resource instanceof Resource castResource) {
        return castResource;
      }
    } catch (Exception e) {
      logger.debug("Could not resolve reference {} from DAO: {}", ref, e.getMessage());
    }

    return null;
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

      IGenericClient client = R4_CTX.newRestfulGenericClient(fhirServerBase);

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
   * Searches the CRD client's FHIR server for the patient's active Coverage.
   * Fallback for when prefetch does not include coverage, per the CRD IG
   * expectation that servers query the EHR for data not returned in prefetch.
   */
  public static List<Coverage> searchActiveCoverageFromServer(String patientId,
      CdsServiceRequestJson request) {

    if (request == null || patientId == null || patientId.isBlank()
        || request.getFhirServer() == null) {
      return List.of();
    }

    try {
      IGenericClient client = R4_CTX.newRestfulGenericClient(request.getFhirServer());

      CdsServiceRequestAuthorizationJson authorization = request.getServiceRequestAuthorizationJson();
      if (authorization != null && authorization.getAccessToken() != null) {
        client.registerInterceptor(new BearerTokenAuthInterceptor(authorization.getAccessToken()));
      }

      Bundle bundle = client.search()
          .forResource(Coverage.class)
          .where(Coverage.PATIENT.hasId(patientId))
          .and(Coverage.STATUS.exactly().code("active"))
          .returnBundle(Bundle.class)
          .execute();

      return BundleUtil.toListOfResourcesOfType(R4_CTX, bundle, Coverage.class);
    } catch (Exception e) {
      logger.warn("Could not search Coverage for patient {} from server: {}", patientId,
          e.getMessage());
      return List.of();
    }
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
      String ref = payorRef.getReference();
      for (Organization org : organizations) {
        // Contained references (#id) won't match via toRelativeReference/referencesMatch
        // because they lack a resource type. Match by fragment ID directly.
        if (ref.startsWith("#") && org.hasIdElement()) {
          String containedId = ref.substring(1);
          if (containedId.equals(org.getIdElement().getIdPart())) {
            matched.add(org);
            continue;
          }
        }
        String orgRef = toRelativeReference(org);
        if (orgRef != null && referencesMatch(ref, orgRef)) {
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

    Class<? extends Resource> resourceClass = OrderResourceTypes.resourceClassFor(resourceType);
    if (resourceClass == null) {
      return null;
    }

    return resolveReference(ref, resourceClass, null, request);
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
    return OrderResourceTypes.isSupported(resource);
  }

  /**
   * Extracts all order resources from a bundle.
   */
  public static List<Resource> extractOrders(Bundle bundle) {
    List<Resource> orders = new ArrayList<>();
    if (bundle == null) {
      return orders;
    }
    for (IBaseResource resource : BundleUtil.toListOfResources(R4_CTX, bundle)) {
      if (resource instanceof Resource castResource && isOrderResource(castResource)) {
        orders.add(castResource);
      }
    }
    return orders;
  }
}
