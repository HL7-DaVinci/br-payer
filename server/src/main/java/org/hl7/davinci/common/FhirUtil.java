package org.hl7.davinci.common;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Resource;

/**
 * Pure FHIR utility methods used across CDS Hooks and DTR.
 */
public final class FhirUtil {

  private FhirUtil() {
  }

  /**
   * Returns the URL with swapped protocol (https↔http), or null if not http/https.
   */
  public static String getAlternateProtocolUrl(String url) {
    if (url == null) {
      return null;
    }
    if (url.startsWith("https://")) {
      return url.replaceFirst("https://", "http://");
    }
    if (url.startsWith("http://")) {
      return url.replaceFirst("http://", "https://");
    }
    return null;
  }

  /**
   * Finds a resource in the parent's contained resources by ID and type.
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
}
