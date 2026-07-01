package org.hl7.davinci.pas;

import static org.hl7.davinci.common.FhirConstants.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.hl7.davinci.common.ResourceResolver;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

/**
 * Resolves Patient/Organization/Coverage references from PAS request bundles.
 * Used by both $submit and $inquire to keep reference matching behavior aligned.
 */
@Component
public class PasBundleReferenceResolver {

  private final DaoRegistry daoRegistry;

  public PasBundleReferenceResolver(DaoRegistry daoRegistry) {
    this.daoRegistry = daoRegistry;
  }

  /**
   * Resolves Patient/Organization/Coverage references from the bundle to server-side resources.
   * When storeMissing is true (submit path), unresolved bundle resources are stored and
   * cross-references are rewritten. When false (inquiry path), references are resolved without storing.
   */
  public void resolveReferences(Bundle requestBundle, Claim claim, boolean storeMissing) {
    Map<String, String> refMap = storeMissing ? new LinkedHashMap<>() : null;
    resolvePatient(requestBundle, claim, refMap);
    resolveOrganization(requestBundle, claim.getInsurer(), refMap);
    resolveOrganization(requestBundle, claim.getProvider(), refMap);
    resolveCoverage(requestBundle, claim, refMap);
  }

  private void resolvePatient(Bundle requestBundle, Claim claim, Map<String, String> refMap) {
    if (claim == null || !claim.hasPatient() || !claim.getPatient().hasReference()) {
      return;
    }

    String patientRef = claim.getPatient().getReference();
    Patient bundlePatient = ResourceResolver.findInBundle(patientRef, Patient.class, requestBundle);
    if (bundlePatient == null) {
      return;
    }

    Identifier memberIdentifier = findMemberIdentifier(bundlePatient);
    if (memberIdentifier == null) {
      return;
    }

    Patient serverPatient = findFirstByIdentifier(Patient.class, memberIdentifier);
    if (serverPatient != null) {
      String resolved = "Patient/" + serverPatient.getIdElement().getIdPart();
      claim.setPatient(new Reference(resolved));
      if (refMap != null) {
        refMap.put(patientRef, resolved);
      }
    } else if (refMap != null) {
      storeIfAbsent(Patient.class, bundlePatient);
    }
  }

  private void resolveOrganization(Bundle requestBundle, Reference orgRef, Map<String, String> refMap) {
    if (orgRef == null || !orgRef.hasReference()) {
      return;
    }

    String originalRef = orgRef.getReference();
    Organization bundleOrg = findOrganizationInBundle(requestBundle, orgRef);
    if (bundleOrg == null) {
      return;
    }

    Identifier npiIdentifier = findNpiIdentifier(bundleOrg);
    if (npiIdentifier == null) {
      return;
    }

    Organization serverOrg = findFirstByIdentifier(Organization.class, npiIdentifier);
    if (serverOrg != null) {
      String resolved = "Organization/" + serverOrg.getIdElement().getIdPart();
      orgRef.setReference(resolved);
      if (refMap != null) {
        refMap.put(originalRef, resolved);
      }
    } else if (refMap != null) {
      storeIfAbsent(Organization.class, bundleOrg);
    }
  }

  private void resolveCoverage(Bundle requestBundle, Claim claim, Map<String, String> refMap) {
    if (claim == null || !claim.hasInsurance() || !claim.getInsuranceFirstRep().getCoverage().hasReference()) {
      return;
    }

    Reference claimCoverageRef = claim.getInsuranceFirstRep().getCoverage();
    String coverageRef = claimCoverageRef.getReference();
    Coverage bundleCoverage = ResourceResolver.findInBundle(coverageRef, Coverage.class, requestBundle);
    if (bundleCoverage == null) {
      return;
    }

    if (bundleCoverage.hasIdentifier()) {
      Identifier covId = bundleCoverage.getIdentifierFirstRep();
      Coverage serverCoverage = findFirstByIdentifier(Coverage.class, covId);
      if (serverCoverage != null) {
        claimCoverageRef.setReference("Coverage/" + serverCoverage.getIdElement().getIdPart());
        return;
      }
    }

    if (refMap != null) {
      rewriteCoverageReferences(bundleCoverage, refMap);
      storeIfAbsent(Coverage.class, bundleCoverage);
    }
  }

  /**
   * Finds the member identifier on a Patient. Prefers MB-typed identifiers per PAS IG,
   * falls back to the first identifier for robustness with sample payloads.
   */
  static Identifier findMemberIdentifier(Patient patient) {
    for (Identifier identifier : patient.getIdentifier()) {
      if (identifier.hasType()) {
        for (Coding coding : identifier.getType().getCoding()) {
          if (IDENTIFIER_TYPE_SYSTEM.equals(coding.getSystem())
              && MB_TYPE_CODE.equals(coding.getCode())) {
            return identifier;
          }
        }
      }
    }
    return patient.hasIdentifier() ? patient.getIdentifierFirstRep() : null;
  }

  static Identifier findNpiIdentifier(Organization organization) {
    for (Identifier identifier : organization.getIdentifier()) {
      if (NPI_SYSTEM.equals(identifier.getSystem())) {
        return identifier;
      }
    }
    return null;
  }

  static Organization findOrganizationInBundle(Bundle requestBundle, Reference orgRef) {
    if (requestBundle == null || orgRef == null || !orgRef.hasReference()) {
      return null;
    }

    return ResourceResolver.findInBundle(orgRef.getReference(), Organization.class, requestBundle);
  }

  static String findOrganizationNpiInBundle(Bundle requestBundle, Reference orgRef) {
    return extractNpiValue(findOrganizationInBundle(requestBundle, orgRef));
  }

  static String extractNpiValue(Organization organization) {
    if (organization == null) {
      return null;
    }

    Identifier npiIdentifier = findNpiIdentifier(organization);
    if (npiIdentifier != null && npiIdentifier.hasValue()) {
      return npiIdentifier.getValue();
    }
    return null;
  }

  private <T extends IBaseResource> T findFirstByIdentifier(Class<T> resourceType, Identifier identifier) {
    if (identifier == null || !identifier.hasValue()) {
      return null;
    }

    SearchParameterMap params = new SearchParameterMap();
    params.add("identifier", new TokenParam(identifier.getSystem(), identifier.getValue()));
    params.setCount(1);
    return daoRegistry.getResourceDao(resourceType)
        .searchForResources(params, new SystemRequestDetails())
        .stream()
        .findFirst()
        .orElse(null);
  }

  /**
   * Stores a bundle resource only when the payer does not already hold one at the same logical
   * id; an existing copy is reused as-is (the payer's own record wins).
   */
  private <T extends IAnyResource> void storeIfAbsent(Class<T> type, T bundleResource) {
    String logicalId = bundleResource.getIdElement().getIdPart();
    if (logicalId != null && existsById(type, logicalId)) {
      return;
    }
    bundleResource.getMeta().setVersionId(null);
    daoRegistry.getResourceDao(type).update(bundleResource, new SystemRequestDetails());
  }

  private boolean existsById(Class<? extends IBaseResource> type, String logicalId) {
    try {
      daoRegistry.getResourceDao(type)
          .read(new IdType(type.getSimpleName(), logicalId), new SystemRequestDetails());
      return true;
    } catch (ResourceNotFoundException | ResourceGoneException e) {
      return false;
    }
  }

  private void rewriteCoverageReferences(Coverage coverage, Map<String, String> refMap) {
    if (coverage == null || refMap == null || refMap.isEmpty()) {
      return;
    }
    rewriteIfMapped(coverage.getBeneficiary(), refMap);
    rewriteIfMapped(coverage.getSubscriber(), refMap);
    for (Reference payorRef : coverage.getPayor()) {
      rewriteIfMapped(payorRef, refMap);
    }
  }

  private void rewriteIfMapped(Reference ref, Map<String, String> refMap) {
    if (ref == null || !ref.hasReference()) {
      return;
    }
    String resolved = refMap.get(ref.getReference());
    if (resolved != null) {
      ref.setReference(resolved);
    }
  }
}
