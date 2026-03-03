package org.hl7.davinci.common;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.IdType;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.SortOrderEnum;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.param.UriParam;

/**
 * Pure FHIR utility methods used across CDS Hooks, DTR, and PAS.
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

  /**
   * Parse a canonical URL into base URL and optional version.
   * Example: "http://example.org/Questionnaire/foo|1.0" returns ["http://example.org/Questionnaire/foo", "1.0"]
   *
   * @return array of [url] or [url, version]
   */
  public static String[] parseCanonical(String canonical) {
    if (canonical == null) {
      return new String[0];
    }
    int pipeIndex = canonical.indexOf('|');
    if (pipeIndex < 0) {
      return new String[]{canonical};
    }
    return new String[]{canonical.substring(0, pipeIndex), canonical.substring(pipeIndex + 1)};
  }

  /**
   * Search for a resource by canonical URL (and optionally version) from the FHIR repository.
   * Returns the most recently updated match, or null if not found.
   */
  public static <T extends IBaseResource> T resolveByCanonical(
      DaoRegistry daoRegistry, Class<T> type, String canonical) {

    if (canonical == null || canonical.isBlank()) {
      return null;
    }

    String[] parts = parseCanonical(canonical);
    SearchParameterMap params = new SearchParameterMap();
    params.add("url", new UriParam(parts[0]));
    if (parts.length > 1) {
      params.add("version", new TokenParam(parts[1]));
    }
    params.setSort(new SortSpec("_lastUpdated", SortOrderEnum.DESC));
    params.setCount(1);

    var results = daoRegistry.getResourceDao(type)
        .searchForResources(params, new SystemRequestDetails());
    if (results.isEmpty()) {
      return null;
    }
    return type.cast(results.get(0));
  }

  /**
   * Build a version-specific canonical: "url|version".
   * Returns the url unchanged if version is null or url already contains a pipe.
   */
  public static String toVersionSpecific(String url, String version) {
    if (url == null) {
      return null;
    }
    if (version != null && !version.isBlank() && !url.contains("|")) {
      return url + "|" + version;
    }
    return url;
  }
}
