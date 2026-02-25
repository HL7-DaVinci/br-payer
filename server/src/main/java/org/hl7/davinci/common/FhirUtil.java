package org.hl7.davinci.common;

import org.hl7.fhir.r4.model.IdType;

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
   * Normalizes a server base URL by removing one trailing slash.
   */
  public static String normalizeServerBase(String base) {
    if (base != null && base.endsWith("/")) {
      return base.substring(0, base.length() - 1);
    }
    return base;
  }

  /**
   * Builds a versionless resource URL using HAPI IdType path handling.
   */
  public static String buildVersionlessResourceUrl(String serverBase, String resourceType, String idPart) {
    if (resourceType == null || resourceType.isBlank() || idPart == null || idPart.isBlank()) {
      return null;
    }
    return new IdType(resourceType, idPart)
        .withServerBase(normalizeServerBase(serverBase), resourceType)
        .toVersionless()
        .getValue();
  }
}
