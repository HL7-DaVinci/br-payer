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
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.NutritionOrder;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;

/**
 * Resolves Patient/Organization/Coverage references from PAS request bundles.
 * Used by both $submit and $inquire to keep reference matching behavior aligned.
 *
 * Logical ids in the request bundle are the provider's server-assigned ids and carry no meaning
 * on this server. Matching is done by business identifier only; unmatched resources are stored
 * under payer-assigned ids and every reference to them is rewritten accordingly.
 */
@Component
public class PasBundleReferenceResolver {

  private final DaoRegistry daoRegistry;

  public PasBundleReferenceResolver(DaoRegistry daoRegistry) {
    this.daoRegistry = daoRegistry;
  }

  /**
   * Resolves Patient/Organization/Practitioner/Coverage references from the bundle to server-side
   * resources, including Claim.careTeam providers. When storeMissing is true (submit path),
   * unresolved bundle resources are stored under payer-assigned ids and cross-references are
   * rewritten. When false (inquiry path), references are resolved without storing.
   */
  public void resolveReferences(Bundle requestBundle, Claim claim, boolean storeMissing) {
    Map<String, String> refMap = storeMissing ? new LinkedHashMap<>() : null;
    resolvePatient(requestBundle, claim, refMap);
    resolveOrganization(requestBundle, claim.getInsurer(), refMap);
    resolveOrganization(requestBundle, claim.getProvider(), refMap);
    resolveCareTeamProviders(requestBundle, claim, refMap);
    resolveCoverage(requestBundle, claim, refMap);
    resolveRequestedServiceOrders(requestBundle, claim, refMap);
    resolveSupportingInfoQuestionnaireResponses(requestBundle, claim, refMap);
  }

  /**
   * Stores the QuestionnaireResponses attached via Claim.supportingInfo (PAS
   * additionalInformation) under payer-assigned ids so the stored Claim's
   * documentation references resolve on this server.
   */
  private void resolveSupportingInfoQuestionnaireResponses(
      Bundle requestBundle, Claim claim, Map<String, String> refMap) {
    if (refMap == null || claim == null || !claim.hasSupportingInfo()) {
      return;
    }
    for (Claim.SupportingInformationComponent info : claim.getSupportingInfo()) {
      if (!(info.getValue() instanceof Reference ref) || !ref.hasReference()
          || rewriteIfMapped(ref, refMap)) {
        continue;
      }
      if (!"QuestionnaireResponse".equals(ResourceResolver.getReferenceResourceType(ref))) {
        continue;
      }
      QuestionnaireResponse qr = ResourceResolver.findInBundle(
          ref.getReference(), QuestionnaireResponse.class, requestBundle);
      if (qr != null) {
        ref.setReference(store(QuestionnaireResponse.class, qr, ref.getReference(), refMap));
      }
    }
  }

  private void resolvePatient(Bundle requestBundle, Claim claim, Map<String, String> refMap) {
    if (claim == null || !claim.hasPatient() || !claim.getPatient().hasReference()) {
      return;
    }

    Reference patientRef = claim.getPatient();
    if (rewriteIfMapped(patientRef, refMap)) {
      return;
    }

    String originalRef = patientRef.getReference();
    Patient bundlePatient = ResourceResolver.findInBundle(originalRef, Patient.class, requestBundle);
    if (bundlePatient == null) {
      return;
    }

    Identifier memberIdentifier = findMemberIdentifier(bundlePatient);
    Patient serverPatient =
        memberIdentifier != null ? findFirstByIdentifier(Patient.class, memberIdentifier) : null;
    if (serverPatient != null) {
      String resolved = "Patient/" + serverPatient.getIdElement().getIdPart();
      patientRef.setReference(resolved);
      if (refMap != null) {
        refMap.put(originalRef, resolved);
      }
    } else if (refMap != null) {
      patientRef.setReference(store(Patient.class, bundlePatient, originalRef, refMap));
    }
  }

  private void resolveOrganization(Bundle requestBundle, Reference orgRef, Map<String, String> refMap) {
    if (orgRef == null || !orgRef.hasReference() || rewriteIfMapped(orgRef, refMap)) {
      return;
    }

    String originalRef = orgRef.getReference();
    Organization bundleOrg = findOrganizationInBundle(requestBundle, orgRef);
    if (bundleOrg == null) {
      return;
    }

    // profile-requestor imposes no identifier constraints; NPI is preferred for matching but a
    // conformant requestor Organization may carry only a non-NPI identifier, or none at all.
    Identifier npiIdentifier = findNpiIdentifier(bundleOrg);
    Identifier matchIdentifier = npiIdentifier != null ? npiIdentifier : findFirstIdentifierWithValue(bundleOrg);

    Organization serverOrg =
        matchIdentifier != null ? findFirstByIdentifier(Organization.class, matchIdentifier) : null;
    if (serverOrg != null) {
      String resolved = "Organization/" + serverOrg.getIdElement().getIdPart();
      orgRef.setReference(resolved);
      if (refMap != null) {
        refMap.put(originalRef, resolved);
      }
    } else if (refMap != null) {
      orgRef.setReference(store(Organization.class, bundleOrg, originalRef, refMap));
    }
  }

  private void resolveCareTeamProviders(Bundle requestBundle, Claim claim, Map<String, String> refMap) {
    if (claim == null || !claim.hasCareTeam()) {
      return;
    }

    for (Claim.CareTeamComponent careTeamMember : claim.getCareTeam()) {
      resolveCareTeamProvider(requestBundle, careTeamMember, refMap);
    }
  }

  private void resolveCareTeamProvider(
      Bundle requestBundle, Claim.CareTeamComponent careTeamMember, Map<String, String> refMap) {
    if (!careTeamMember.hasProvider() || !careTeamMember.getProvider().hasReference()) {
      return;
    }

    Reference providerRef = careTeamMember.getProvider();
    if (rewriteIfMapped(providerRef, refMap)) {
      return;
    }

    if (findOrganizationInBundle(requestBundle, providerRef) != null) {
      resolveOrganization(requestBundle, providerRef, refMap);
      return;
    }

    PractitionerRole bundleRole =
        ResourceResolver.findInBundle(providerRef.getReference(), PractitionerRole.class, requestBundle);
    if (bundleRole != null) {
      resolveCareTeamProviderRole(requestBundle, bundleRole, providerRef, refMap);
      return;
    }

    resolvePractitioner(requestBundle, providerRef, refMap);
  }

  /**
   * Resolves the PractitionerRole's internal practitioner/organization references before storing
   * the role itself, so the stored role points at payer-side resources. PractitionerRole carries
   * no identifier in practice, so it is stored without server-side identifier matching,
   * consistent with the no-identifier Organization case.
   */
  private void resolveCareTeamProviderRole(Bundle requestBundle, PractitionerRole bundleRole,
      Reference providerRef, Map<String, String> refMap) {
    if (bundleRole.hasPractitioner() && bundleRole.getPractitioner().hasReference()) {
      resolvePractitioner(requestBundle, bundleRole.getPractitioner(), refMap);
    }
    if (bundleRole.hasOrganization() && bundleRole.getOrganization().hasReference()) {
      resolveOrganization(requestBundle, bundleRole.getOrganization(), refMap);
    }

    if (refMap != null) {
      providerRef.setReference(
          store(PractitionerRole.class, bundleRole, providerRef.getReference(), refMap));
    }
  }

  private void resolvePractitioner(Bundle requestBundle, Reference practitionerRef, Map<String, String> refMap) {
    if (rewriteIfMapped(practitionerRef, refMap)) {
      return;
    }

    String originalRef = practitionerRef.getReference();
    Practitioner bundlePractitioner =
        ResourceResolver.findInBundle(originalRef, Practitioner.class, requestBundle);
    if (bundlePractitioner == null) {
      return;
    }

    Identifier npiIdentifier = findNpiIdentifier(bundlePractitioner);
    Identifier matchIdentifier =
        npiIdentifier != null ? npiIdentifier : findFirstIdentifierWithValue(bundlePractitioner);

    Practitioner serverPractitioner =
        matchIdentifier != null ? findFirstByIdentifier(Practitioner.class, matchIdentifier) : null;
    if (serverPractitioner != null) {
      String resolved = "Practitioner/" + serverPractitioner.getIdElement().getIdPart();
      practitionerRef.setReference(resolved);
      if (refMap != null) {
        refMap.put(originalRef, resolved);
      }
    } else if (refMap != null) {
      practitionerRef.setReference(store(Practitioner.class, bundlePractitioner, originalRef, refMap));
    }
  }

  private void resolveCoverage(Bundle requestBundle, Claim claim, Map<String, String> refMap) {
    if (claim == null || !claim.hasInsurance() || !claim.getInsuranceFirstRep().getCoverage().hasReference()) {
      return;
    }

    Reference claimCoverageRef = claim.getInsuranceFirstRep().getCoverage();
    if (rewriteIfMapped(claimCoverageRef, refMap)) {
      return;
    }

    String coverageRef = claimCoverageRef.getReference();
    Coverage bundleCoverage = ResourceResolver.findInBundle(coverageRef, Coverage.class, requestBundle);
    if (bundleCoverage == null) {
      return;
    }

    if (bundleCoverage.hasIdentifier()) {
      Identifier covId = bundleCoverage.getIdentifierFirstRep();
      Coverage serverCoverage = findFirstByIdentifier(Coverage.class, covId);
      if (serverCoverage != null) {
        String resolved = "Coverage/" + serverCoverage.getIdElement().getIdPart();
        claimCoverageRef.setReference(resolved);
        if (refMap != null) {
          refMap.put(coverageRef, resolved);
        }
        return;
      }
    }

    if (refMap != null) {
      claimCoverageRef.setReference(store(Coverage.class, bundleCoverage, coverageRef, refMap));
    }
  }

  /**
   * Stores the order resource (DeviceRequest/ServiceRequest/MedicationRequest/NutritionOrder)
   * referenced from each Claim.item's requestedService extension under a payer-assigned id, so
   * the stored Claim's reference resolves on this server.
   */
  private void resolveRequestedServiceOrders(Bundle requestBundle, Claim claim, Map<String, String> refMap) {
    if (refMap == null || claim == null || !claim.hasItem()) {
      return;
    }

    for (Claim.ItemComponent item : claim.getItem()) {
      Extension requested = item.getExtensionByUrl(PasConstants.ITEM_REQUESTED_SERVICE);
      if (requested == null || !(requested.getValue() instanceof Reference orderRef)) {
        continue;
      }
      storeRequestedServiceOrder(requestBundle, orderRef, refMap);
    }
  }

  private void storeRequestedServiceOrder(Bundle requestBundle, Reference orderRef, Map<String, String> refMap) {
    if (!orderRef.hasReference() || rewriteIfMapped(orderRef, refMap)) {
      return;
    }

    String resourceType = ResourceResolver.getReferenceResourceType(orderRef);
    if (resourceType == null) {
      return;
    }

    switch (resourceType) {
      case "DeviceRequest" -> storeOrderFromBundle(requestBundle, orderRef, DeviceRequest.class, refMap);
      case "ServiceRequest" -> storeOrderFromBundle(requestBundle, orderRef, ServiceRequest.class, refMap);
      case "MedicationRequest" -> storeOrderFromBundle(requestBundle, orderRef, MedicationRequest.class, refMap);
      case "NutritionOrder" -> storeOrderFromBundle(requestBundle, orderRef, NutritionOrder.class, refMap);
      default -> {
      }
    }
  }

  private <T extends IAnyResource> void storeOrderFromBundle(
      Bundle requestBundle, Reference orderRef, Class<T> resourceType, Map<String, String> refMap) {
    T order = ResourceResolver.findInBundle(orderRef.getReference(), resourceType, requestBundle);
    if (order != null) {
      orderRef.setReference(store(resourceType, order, orderRef.getReference(), refMap));
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

  static Identifier findFirstIdentifierWithValue(Organization organization) {
    for (Identifier identifier : organization.getIdentifier()) {
      if (identifier.hasValue()) {
        return identifier;
      }
    }
    return null;
  }

  static Identifier findNpiIdentifier(Practitioner practitioner) {
    for (Identifier identifier : practitioner.getIdentifier()) {
      if (NPI_SYSTEM.equals(identifier.getSystem())) {
        return identifier;
      }
    }
    return null;
  }

  static Identifier findFirstIdentifierWithValue(Practitioner practitioner) {
    for (Identifier identifier : practitioner.getIdentifier()) {
      if (identifier.hasValue()) {
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
   * Stores a bundle resource under a payer-assigned id after rewriting its internal references
   * to already-resolved resources. The new id is stamped back onto the bundle resource so
   * later in-bundle lookups by the rewritten reference still resolve, and the mapping is
   * recorded so every other reference to the same bundle resource is rewritten too.
   */
  private <T extends IAnyResource> String store(
      Class<T> type, T bundleResource, String originalRef, Map<String, String> refMap) {
    rewriteMappedReferences(bundleResource, refMap);
    clearUnresolvedRelativeReferences(bundleResource, refMap);

    String bundleLocalRef = bundleResource.getIdElement().getIdPart() == null
        ? null
        : type.getSimpleName() + "/" + bundleResource.getIdElement().getIdPart();
    bundleResource.setId((String) null);
    bundleResource.getMeta().setVersionId(null);

    DaoMethodOutcome outcome = daoRegistry.getResourceDao(type)
        .create(bundleResource, new SystemRequestDetails());
    String resolved = type.getSimpleName() + "/" + outcome.getId().getIdPart();
    bundleResource.setId(outcome.getId().toUnqualifiedVersionless());

    refMap.put(originalRef, resolved);
    if (bundleLocalRef != null) {
      refMap.put(bundleLocalRef, resolved);
    }
    return resolved;
  }

  private void rewriteMappedReferences(IAnyResource resource, Map<String, String> refMap) {
    if (refMap.isEmpty()) {
      return;
    }
    for (Reference ref : FhirContext.forR4Cached().newTerser()
        .getAllPopulatedChildElementsOfType(resource, Reference.class)) {
      rewriteIfMapped(ref, refMap);
    }
  }

  /**
   * Clears relative references that did not resolve within the bundle. They carry
   * provider logical ids that cannot exist on this server, so keeping them would
   * either fail referential integrity or point at unrelated local resources.
   * Identifier and display are preserved; absolute and contained references are
   * left alone.
   */
  private void clearUnresolvedRelativeReferences(IAnyResource resource, Map<String, String> refMap) {
    java.util.Set<String> resolved = new java.util.HashSet<>(refMap.values());
    for (Reference ref : FhirContext.forR4Cached().newTerser()
        .getAllPopulatedChildElementsOfType(resource, Reference.class)) {
      String value = ref.getReference();
      if (value == null || value.startsWith("#") || value.contains("://") || resolved.contains(value)) {
        continue;
      }
      ref.setReference(null);
    }
  }

  private boolean rewriteIfMapped(Reference ref, Map<String, String> refMap) {
    if (refMap == null || ref == null || !ref.hasReference()) {
      return false;
    }
    String resolved = refMap.get(ref.getReference());
    if (resolved != null) {
      ref.setReference(resolved);
      return true;
    }
    return false;
  }
}
