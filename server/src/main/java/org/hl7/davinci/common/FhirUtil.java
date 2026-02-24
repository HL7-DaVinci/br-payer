package org.hl7.davinci.common;

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
}
