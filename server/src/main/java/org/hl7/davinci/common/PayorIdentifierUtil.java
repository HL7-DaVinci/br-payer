package org.hl7.davinci.common;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;

/**
 * Shared payor identifier extraction and filtering helpers.
 */
public final class PayorIdentifierUtil {

  private PayorIdentifierUtil() {
  }

  /**
   * Returns valid Organization identifiers for payors referenced by Coverage.
   */
  public static List<Identifier> extractFromCoverageAndOrganizations(
      Coverage coverage, List<Organization> organizations) {
    List<Identifier> identifiers = new ArrayList<>();
    if (coverage == null || organizations == null) {
      return identifiers;
    }

    for (Organization org : ResourceResolver.findPayorOrganizations(coverage, organizations)) {
      addValidIdentifiers(identifiers, org);
    }
    return identifiers;
  }

  /**
   * Returns valid Organization identifiers for the first Coverage.payor resolved in a Bundle.
   */
  public static List<Identifier> extractFirstFromCoverageAndBundle(Coverage coverage, Bundle bundle) {
    if (coverage == null || bundle == null || !coverage.hasPayor()) {
      return List.of();
    }

    for (Reference payorRef : coverage.getPayor()) {
      String ref = payorRef.getReference();
      if (ref == null || ref.isBlank()) {
        continue;
      }
      Organization organization = ResourceResolver.findInBundle(ref, Organization.class, bundle);
      if (organization != null) {
        return validIdentifiers(organization);
      }
    }
    return List.of();
  }

  /**
   * Returns only identifiers that have both system and value.
   */
  public static List<Identifier> validIdentifiers(Organization organization) {
    List<Identifier> identifiers = new ArrayList<>();
    addValidIdentifiers(identifiers, organization);
    return identifiers;
  }

  /**
   * Adds only identifiers that have both system and value.
   */
  public static void addValidIdentifiers(List<Identifier> target, Organization organization) {
    if (target == null || organization == null) {
      return;
    }
    for (Identifier identifier : organization.getIdentifier()) {
      if (identifier.hasSystem() && identifier.hasValue()) {
        target.add(identifier);
      }
    }
  }
}

