package org.hl7.davinci.pas;

import java.util.LinkedHashMap;
import java.util.Map;

import org.hl7.davinci.common.ResourceResolver;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;

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
   * Resolves references and stores missing bundle resources to preserve linkage for submit workflows.
   */
  public void resolveAndStoreBundleResources(Bundle requestBundle, Claim claim) {
    Map<String, String> refMap = new LinkedHashMap<>();
    resolvePatient(requestBundle, claim, refMap, true);
    resolveOrganization(requestBundle, claim.getInsurer(), refMap, true);
    resolveOrganization(requestBundle, claim.getProvider(), refMap, true);
    resolveCoverage(requestBundle, claim, refMap, true);
  }

  /**
   * Resolves references without storing missing bundle resources for inquiry workflows.
   */
  public void resolveInquiryReferences(Bundle requestBundle, Claim claim) {
    resolvePatient(requestBundle, claim, null, false);
    resolveOrganization(requestBundle, claim.getInsurer(), null, false);
    resolveOrganization(requestBundle, claim.getProvider(), null, false);
    resolveCoverage(requestBundle, claim, null, false);
  }

  private void resolvePatient(Bundle requestBundle, Claim claim, Map<String, String> refMap,
      boolean storeMissing) {
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
    } else if (storeMissing) {
      daoRegistry.getResourceDao(Patient.class).update(bundlePatient, new SystemRequestDetails());
    }
  }

  private void resolveOrganization(Bundle requestBundle, Reference orgRef, Map<String, String> refMap,
      boolean storeMissing) {
    if (orgRef == null || !orgRef.hasReference()) {
      return;
    }

    String originalRef = orgRef.getReference();
    Organization bundleOrg = ResourceResolver.findInBundle(originalRef, Organization.class, requestBundle);
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
    } else if (storeMissing) {
      daoRegistry.getResourceDao(Organization.class).update(bundleOrg, new SystemRequestDetails());
    }
  }

  private void resolveCoverage(Bundle requestBundle, Claim claim, Map<String, String> refMap,
      boolean storeMissing) {
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

    if (storeMissing) {
      rewriteCoverageReferences(bundleCoverage, refMap);
      daoRegistry.getResourceDao(Coverage.class).update(bundleCoverage, new SystemRequestDetails());
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
          if (PasConstants.MB_TYPE_SYSTEM.equals(coding.getSystem())
              && PasConstants.MB_TYPE_CODE.equals(coding.getCode())) {
            return identifier;
          }
        }
      }
    }
    return patient.hasIdentifier() ? patient.getIdentifierFirstRep() : null;
  }

  static Identifier findNpiIdentifier(Organization organization) {
    for (Identifier identifier : organization.getIdentifier()) {
      if (PasConstants.NPI_SYSTEM.equals(identifier.getSystem())) {
        return identifier;
      }
    }
    return null;
  }

  private <T extends IBaseResource> T findFirstByIdentifier(Class<T> resourceType, Identifier identifier) {
    if (identifier == null || !identifier.hasValue()) {
      return null;
    }

    SearchParameterMap params = new SearchParameterMap();
    params.add("identifier", new TokenParam(identifier.getSystem(), identifier.getValue()));

    IBundleProvider results = daoRegistry.getResourceDao(resourceType)
        .search(params, new SystemRequestDetails());

    return results.getResources(0, 1).stream()
        .filter(resourceType::isInstance)
        .map(resourceType::cast)
        .findFirst()
        .orElse(null);
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
