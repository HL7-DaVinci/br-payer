package org.hl7.davinci.common;

import java.util.Set;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;

/**
 * Shared helpers for adding resources to Bundles with de-duplication.
 */
public final class BundleResourceUtil {

  private BundleResourceUtil() {
  }

  public static boolean addByVersionlessIdentity(Bundle bundle, Set<String> seen, Resource resource) {
    return add(bundle, seen, resource, false);
  }

  public static boolean addByUnqualifiedVersionlessIdentity(Bundle bundle, Set<String> seen, Resource resource) {
    return add(bundle, seen, resource, true);
  }

  private static boolean add(Bundle bundle, Set<String> seen, Resource resource, boolean unqualified) {
    if (bundle == null || resource == null) {
      return false;
    }

    String identity = identity(resource, unqualified);
    if (identity == null || identity.isBlank()) {
      identity = resource.fhirType() + "@" + System.identityHashCode(resource);
    }
    if (seen == null || seen.add(identity)) {
      bundle.addEntry().setResource(resource);
      return true;
    }
    return false;
  }

  private static String identity(Resource resource, boolean unqualified) {
    if (resource == null || !resource.hasIdElement()) {
      return null;
    }

    String identity = unqualified
        ? resource.getIdElement().toUnqualifiedVersionless().getValue()
        : resource.getIdElement().toVersionless().getValue();
    if (identity != null && !identity.isBlank()) {
      return identity;
    }

    String idPart = resource.getIdElement().getIdPart();
    if (idPart == null || idPart.isBlank()) {
      return null;
    }
    return resource.fhirType() + "/" + idPart;
  }
}

