package org.hl7.davinci.scenarios;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hl7.davinci.pas.PasConstants;
import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;

/**
 * Builds PAS Request Bundles from ScenarioMetadata.
 * Produces $submit variants (initial, renewal, update, cancel) and an $inquire
 * variant per scenario.
 * Pure FHIR model logic with no Spring or I/O dependencies.
 */
public class PasRequestBuilder {

  // Profile URLs referenced from PasExtensions for request/inquiry bundles
  static final String PROFILE_PAS_REQUEST_BUNDLE = PasConstants.PROFILE_PAS_REQUEST_BUNDLE;
  static final String PROFILE_PAS_INQUIRY_REQUEST_BUNDLE = PasConstants.PROFILE_PAS_INQUIRY_REQUEST_BUNDLE;

  // X12 code systems
  static final String X12_CERT_TYPE_SYSTEM = "https://codesystem.x12.org/005010/1322";
  static final String X12_SERVICE_TYPE_SYSTEM = "https://codesystem.x12.org/005010/1525";
  static final String X12_REQUESTED_SERVICE_SYSTEM = "https://codesystem.x12.org/005010/1365";
  static final String CPT_SYSTEM = "http://www.ama-assn.org/go/cpt";
  static final String HCPCS_SYSTEM = "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";
  static final String ICD9CM_SYSTEM = "http://terminology.hl7.org/CodeSystem/icd9cm";
  static final String ICD10_SYSTEM = "http://www.cms.gov/Medicare/Coding/ICD10";
  static final String NDC_SYSTEM = "http://hl7.org/fhir/sid/ndc";
  static final String DATA_ABSENT_REASON_SYSTEM = "http://terminology.hl7.org/CodeSystem/data-absent-reason";
  static final String DEFAULT_REQUESTED_SERVICE_CODE = "3";
  static final String MEMBER_ID_SYSTEM = "http://example.org/MIN";
  static final String V2_IDENTIFIER_TYPE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v2-0203";
  private static final Set<String> PAS_REQUESTED_SERVICE_SYSTEMS = Set.of(
      X12_REQUESTED_SERVICE_SYSTEM,
      CPT_SYSTEM,
      HCPCS_SYSTEM,
      ICD9CM_SYSTEM,
      ICD10_SYSTEM,
      NDC_SYSTEM,
      DATA_ABSENT_REASON_SYSTEM);

  // NPI system
  static final String NPI_SYSTEM = "http://hl7.org/fhir/sid/us-npi";

  // Shared resource IDs matching examples-pas seed data
  static final String PATIENT_ID = "BeneficiaryExample";
  static final String INSURER_ID = "InsurerExample";
  static final String PROVIDER_ID = "UMOExample";
  static final String COVERAGE_ID = "InsuranceExample";
  static final String PRACTITIONER_ROLE_ID = "ReferralPractitionerRoleExample";
  static final String PRACTITIONER_ID = "ReferralPractitionerExample";

  private PasRequestBuilder() {
  }

  /**
   * Build PAS scenarios with $submit variants (initial, renewal, update, cancel)
   * and $inquire.
   */
  public static List<PasScenario> build(List<ScenarioMetadata> metadataList) {
    SeedResources seed = buildSeedResources();
    List<PasScenario> result = new ArrayList<>();

    for (ScenarioMetadata meta : metadataList) {
      if (meta.focusCodes().isEmpty()) {
        continue;
      }

      Coding focusCode = meta.focusCodes().get(0);
      String description = ScenarioResourceUtil.buildDescription(meta);

      // Shared trace number links the initial submission with its update/cancel/inquiry variants
      String initialTraceNumber = UUID.randomUUID().toString();

      List<PasVariant> variants = new ArrayList<>();
      // $submit variants -- all target the same Claim/$submit endpoint
      variants.add(new PasVariant(
          meta.id() + "-initial", "Initial", "$submit", "initial",
          buildSubmitBundle(meta, focusCode, seed, "I", "Initial", initialTraceNumber)));
      variants.add(new PasVariant(
          meta.id() + "-renewal", "Renewal", "$submit", "renewal",
          buildSubmitBundle(meta, focusCode, seed, "R", "Renewal", UUID.randomUUID().toString())));
      variants.add(new PasVariant(
          meta.id() + "-update", "Update", "$submit", "update",
          buildUpdateBundle(meta, focusCode, seed, initialTraceNumber)));
      variants.add(new PasVariant(
          meta.id() + "-cancel", "Cancel", "$submit", "cancel",
          buildCancelBundle(meta, focusCode, seed, initialTraceNumber)));
      // $inquire variant -- targets the Claim/$inquire endpoint
      variants.add(new PasVariant(
          meta.id() + "-inquiry", "Inquiry", "$inquire", "inquiry",
          buildInquiryBundle(meta, focusCode, seed, initialTraceNumber)));

      result.add(new PasScenario(meta.id(), meta.name(), description,
          meta.orderType() != null ? meta.orderType() : "ServiceRequest",
          variants));
    }

    return result;
  }

  // ===== Bundle construction =====

  static Bundle buildSubmitBundle(ScenarioMetadata meta, Coding focusCode, SeedResources seed,
      String certTypeCode, String certTypeDisplay, String traceNumber) {
    ServiceRequest serviceRequest = buildServiceRequest(meta, focusCode);
    Claim claim = buildClaim(meta, focusCode, seed, serviceRequest, certTypeCode, certTypeDisplay, traceNumber);
    claim.getMeta().addProfile(PasConstants.PROFILE_PAS_CLAIM);
    return wrapInBundle(PROFILE_PAS_REQUEST_BUNDLE, claim, seed, false, serviceRequest);
  }

  static Bundle buildInquiryBundle(ScenarioMetadata meta, Coding focusCode, SeedResources seed) {
    return buildInquiryBundle(meta, focusCode, seed, UUID.randomUUID().toString());
  }

  static Bundle buildInquiryBundle(ScenarioMetadata meta, Coding focusCode, SeedResources seed,
      String traceNumber) {
    ServiceRequest serviceRequest = buildServiceRequest(meta, focusCode);
    Claim claim = buildClaim(meta, focusCode, seed, serviceRequest, "I", "Initial",
        traceNumber);
    claim.getMeta().addProfile(PasConstants.PROFILE_PAS_CLAIM_INQUIRY);
    return wrapInBundle(PROFILE_PAS_INQUIRY_REQUEST_BUNDLE, claim, seed, true, serviceRequest);
  }

  static Bundle buildUpdateBundle(ScenarioMetadata meta, Coding focusCode, SeedResources seed,
      String initialTraceNumber) {
    ServiceRequest serviceRequest = buildServiceRequest(meta, focusCode);
    Claim claim = buildClaim(meta, focusCode, seed, serviceRequest, "I", "Initial",
        UUID.randomUUID().toString());
    claim.getMeta().addProfile(PasConstants.PROFILE_PAS_CLAIM_UPDATE);
    String priorClaimId = meta.id() + "-prior-auth-claim";
    addPriorRelatedClaim(claim, priorClaimId);
    // PAS IG: infoChanged is a regular extension on each Claim.item with a
    // valueCode
    for (Claim.ItemComponent item : claim.getItem()) {
      item.addExtension(new Extension(PasConstants.INFO_CHANGED, new CodeType("changed")));
    }
    Bundle bundle = wrapInBundle(PROFILE_PAS_REQUEST_BUNDLE, claim, seed, false, serviceRequest);
    addPriorClaimToBundle(bundle, priorClaimId, initialTraceNumber);
    return bundle;
  }

  static Bundle buildCancelBundle(ScenarioMetadata meta, Coding focusCode, SeedResources seed,
      String initialTraceNumber) {
    ServiceRequest serviceRequest = buildServiceRequest(meta, focusCode);
    Claim claim = buildClaim(meta, focusCode, seed, serviceRequest, "I", "Initial",
        UUID.randomUUID().toString());
    claim.getMeta().addProfile(PasConstants.PROFILE_PAS_CLAIM_UPDATE);
    String priorClaimId = meta.id() + "-prior-auth-claim";
    addPriorRelatedClaim(claim, priorClaimId);
    // Claim-level certificationType "3" for whole-authorization cancel
    claim.addExtension(PasConstants.CERTIFICATION_TYPE,
        new CodeableConcept().addCoding(new Coding(X12_CERT_TYPE_SYSTEM, "3", "Cancel")));
    // PAS IG: infoCancelled is a modifier extension on each Claim.item
    for (Claim.ItemComponent item : claim.getItem()) {
      item.addModifierExtension(
          new Extension(PasConstants.INFO_CANCELLED, new org.hl7.fhir.r4.model.BooleanType(true)));
    }
    Bundle bundle = wrapInBundle(PROFILE_PAS_REQUEST_BUNDLE, claim, seed, false, serviceRequest);
    addPriorClaimToBundle(bundle, priorClaimId, initialTraceNumber);
    return bundle;
  }

  // ===== Claim construction =====

  static Claim buildClaim(ScenarioMetadata meta, Coding focusCode, SeedResources seed,
      ServiceRequest serviceRequest, String certTypeCode, String certTypeDisplay,
      String traceNumber) {
    Claim claim = new Claim();
    claim.setId(meta.id() + "-claim");

    claim.setStatus(Claim.ClaimStatus.ACTIVE);
    claim.setUse(Claim.Use.PREAUTHORIZATION);
    claim.setCreated(new Date());
    // Claim.identifier 1..1 per PAS Claim profile (used as primary X12 trace
    // reference)
    claim.addIdentifier()
        .setSystem("http://example.org/PATIENT_EVENT_TRACE_NUMBER")
        .setValue(traceNumber);

    // Professional claim type
    claim.setType(new CodeableConcept().addCoding(new Coding(
        "http://terminology.hl7.org/CodeSystem/claim-type", "professional", "Professional")));
    claim.setPriority(new CodeableConcept().addCoding(new Coding(
        "http://terminology.hl7.org/CodeSystem/processpriority", "normal", "Normal")));

    // Required references
    claim.setPatient(new Reference("Patient/" + PATIENT_ID));
    claim.setInsurer(new Reference("Organization/" + INSURER_ID));
    claim.setProvider(new Reference("Organization/" + PROVIDER_ID));

    // Insurance with focal coverage
    claim.addInsurance()
        .setSequence(1)
        .setFocal(true)
        .setCoverage(new Reference("Coverage/" + COVERAGE_ID));

    // Care team
    claim.addCareTeam()
        .setSequence(1)
        .setProvider(new Reference("PractitionerRole/" + PRACTITIONER_ROLE_ID));
    claim.getCareTeam().get(0).addExtension(PasConstants.CARE_TEAM_CLAIM_SCOPE, new BooleanType(true));

    // Item with focus code and required PAS extensions
    Claim.ItemComponent item = claim.addItem();
    item.setSequence(1);
    item.setCategory(new CodeableConcept().addCoding(new Coding(
        "https://codesystem.x12.org/005010/1365", "3", "Consultation")));
    item.setCareTeamSequence(List.of(new org.hl7.fhir.r4.model.PositiveIntType(1)));
    item.setLocation(new CodeableConcept().addCoding(new Coding(
        "https://www.cms.gov/Medicare/Coding/place-of-service-codes/Place_of_Service_Code_Set", "11", "Office")));
    item.setProductOrService(new CodeableConcept().addCoding(normalizeRequestedServiceCoding(focusCode)));

    item.addExtension(PasConstants.CERTIFICATION_TYPE,
        new CodeableConcept().addCoding(new Coding(X12_CERT_TYPE_SYSTEM, certTypeCode, certTypeDisplay)));

    // serviceItemRequestType = "HS" (Health Services Review)
    item.addExtension(PasConstants.SERVICE_ITEM_REQUEST_TYPE,
        new CodeableConcept().addCoding(new Coding(X12_SERVICE_TYPE_SYSTEM, "HS",
            "Health Services Review")));

    // itemTraceNumber
    item.addExtension(PasConstants.ITEM_TRACE_NUMBER,
        new Identifier().setSystem("http://example.org/ITEM_TRACE_NUMBER")
            .setValue(meta.id() + "-trace"));

    // requestedService reference
    item.addExtension(PasConstants.ITEM_REQUESTED_SERVICE,
        new Reference("ServiceRequest/" + serviceRequest.getId()));

    return claim;
  }

  static ServiceRequest buildServiceRequest(ScenarioMetadata meta, Coding focusCode) {
    ServiceRequest sr = new ServiceRequest();
    sr.setId(meta.id() + "-service-request");
    sr.getMeta().addProfile(PasConstants.PROFILE_PAS_SERVICE_REQUEST);
    sr.setStatus(ServiceRequest.ServiceRequestStatus.ACTIVE);
    sr.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
    sr.setCode(new CodeableConcept().addCoding(normalizeRequestedServiceCoding(focusCode)));
    sr.setSubject(new Reference("Patient/" + PATIENT_ID));
    return sr;
  }

  /**
   * Adds a prior Claim resource to the bundle so that the Claim.related.claim
   * reference can be resolved within the bundle. Includes a Claim.identifier
   * (trace number) matching the original submission so the payer can link
   * it to the stored copy via identifier-based search.
   */
  static void addPriorClaimToBundle(Bundle bundle, String priorClaimId, String traceNumber) {
    Claim priorClaim = new Claim();
    priorClaim.setId(priorClaimId);
    priorClaim.setStatus(Claim.ClaimStatus.ACTIVE);
    priorClaim.setUse(Claim.Use.PREAUTHORIZATION);
    priorClaim.addIdentifier()
        .setSystem("http://example.org/PATIENT_EVENT_TRACE_NUMBER")
        .setValue(traceNumber);
    addEntry(bundle, "Claim/" + priorClaimId, priorClaim);
  }

  static void addPriorRelatedClaim(Claim claim, String priorClaimId) {
    claim.addRelated()
        .setClaim(new Reference("Claim/" + priorClaimId))
        .setRelationship(new CodeableConcept().addCoding(new Coding(
            "http://terminology.hl7.org/CodeSystem/ex-relatedclaimrelationship", "prior",
            "Prior Claim")));
  }

  /**
   * PAS ServiceRequest.code and Claim.item.productOrService are bound to the
   * X12 278 Requested Service Type value set. Preserve supported systems and
   * fall back to a generic X12 service type when scenario focus codes are
   * outside that binding.
   */
  static Coding normalizeRequestedServiceCoding(Coding focusCode) {
    if (focusCode != null
        && PAS_REQUESTED_SERVICE_SYSTEMS.contains(focusCode.getSystem())
        && focusCode.hasCode()) {
      Coding normalized = focusCode.copy();
      // Display text from source scenarios may not match terminology package text.
      normalized.setDisplay(null);
      return normalized;
    }
    return new Coding(X12_REQUESTED_SERVICE_SYSTEM, DEFAULT_REQUESTED_SERVICE_CODE, null);
  }

  // ===== Bundle wrapping =====

  static Bundle wrapInBundle(String profileUrl, Claim claim, SeedResources seed,
      boolean useInquiryPatient, ServiceRequest serviceRequest) {
    Bundle bundle = new Bundle();
    bundle.getMeta().addProfile(profileUrl);
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.setTimestamp(new Date());
    bundle.setIdentifier(new Identifier()
        .setSystem("http://example.org/SUBMITTER_TRANSACTION_IDENTIFIER")
        .setValue(UUID.randomUUID().toString()));

    // First entry must be the Claim
    addEntry(bundle, "Claim/" + claim.getId(), claim);

    // Supporting resources
    Patient patient = useInquiryPatient ? seed.inquiryPatient() : seed.patient();
    addEntry(bundle, "Patient/" + PATIENT_ID, patient);
    addEntry(bundle, "Organization/" + INSURER_ID, seed.insurer());
    addEntry(bundle, "Organization/" + PROVIDER_ID, seed.provider());
    addEntry(bundle, "Coverage/" + COVERAGE_ID, seed.coverage());
    addEntry(bundle, "PractitionerRole/" + PRACTITIONER_ROLE_ID, seed.practitionerRole());
    addEntry(bundle, "Practitioner/" + PRACTITIONER_ID, seed.practitioner());
    addEntry(bundle, "ServiceRequest/" + serviceRequest.getId(), serviceRequest);

    return bundle;
  }

  static void addEntry(Bundle bundle, String fullUrl, org.hl7.fhir.r4.model.Resource resource) {
    bundle.addEntry()
        .setFullUrl("http://example.org/fhir/" + fullUrl)
        .setResource(resource);
  }

  // ===== Seed resource construction =====

  static SeedResources buildSeedResources() {
    Patient patient = buildPatient();
    Patient inquiryPatient = buildPatient();
    Organization insurer = buildInsurer();
    Organization provider = buildProvider();
    Coverage coverage = buildCoverage();
    PractitionerRole practitionerRole = buildPractitionerRole();
    Practitioner practitioner = buildPractitioner();
    return new SeedResources(patient, inquiryPatient, insurer, provider, coverage,
        practitionerRole, practitioner);
  }

  static Patient buildPatient() {
    Patient patient = new Patient();
    patient.setId(PATIENT_ID);
    patient.getMeta().addProfile(PasConstants.PROFILE_PAS_BENEFICIARY);
    patient.getMeta().addProfile(PasConstants.PROFILE_PAS_SUBSCRIBER);

    patient.addIdentifier()
        .setSystem(MEMBER_ID_SYSTEM)
        .setValue("12345678901");

    // Keep a typed member identifier for both submit and inquire variants so the
    // same patient can satisfy beneficiary and subscriber references.
    patient.addIdentifier()
        .setType(buildMemberIdType())
        .setSystem(MEMBER_ID_SYSTEM)
        .setValue("12345678901");

    patient.addName().setFamily("SMITH").addGiven("JOE");
    patient.addTelecom()
        .setSystem(ContactPoint.ContactPointSystem.PHONE)
        .setValue("555-0100");
    patient.addCommunication()
        .setLanguage(new CodeableConcept()
            .addCoding(new Coding("urn:ietf:bcp:47", "en", "English")));
    patient.addLink()
        .setOther(new Reference("Patient/" + PATIENT_ID))
        .setType(Patient.LinkType.SEEALSO);
    patient.setGender(Enumerations.AdministrativeGender.MALE);
    return patient;
  }

  static Organization buildInsurer() {
    Organization org = new Organization();
    org.setId(INSURER_ID);
    org.getMeta().addProfile(PasConstants.PROFILE_PAS_INSURER);
    org.setActive(true);
    org.addIdentifier().setSystem(NPI_SYSTEM).setValue("1234567893");
    org.addType().addCoding(new Coding("https://codesystem.x12.org/005010/98", "PR", null));
    org.setName("MARYLAND CAPITAL INSURANCE COMPANY");
    return org;
  }

  static Organization buildProvider() {
    Organization org = new Organization();
    org.setId(PROVIDER_ID);
    org.getMeta().addProfile(PasConstants.PROFILE_PAS_REQUESTOR);
    org.setActive(true);
    org.addIdentifier().setSystem(NPI_SYSTEM).setValue("8189991234");
    org.addType().addCoding(new Coding("https://codesystem.x12.org/005010/98", "X3", null));
    org.setName("DR. JOE SMITH CORPORATION");
    return org;
  }

  static Coverage buildCoverage() {
    Coverage coverage = new Coverage();
    coverage.setId(COVERAGE_ID);
    coverage.getMeta().addProfile(PasConstants.PROFILE_PAS_COVERAGE);
    coverage.setStatus(Coverage.CoverageStatus.ACTIVE);
    coverage.setSubscriberId("1122334455");
    coverage.addIdentifier()
        .setType(buildMemberIdType())
        .setSystem(MEMBER_ID_SYSTEM)
        .setValue(coverage.getSubscriberId());
    coverage.setSubscriber(new Reference("Patient/" + PATIENT_ID));
    coverage.setBeneficiary(new Reference("Patient/" + PATIENT_ID));
    coverage.getRelationship()
        .addCoding(new Coding(
            "http://terminology.hl7.org/CodeSystem/subscriber-relationship", "self", "Self"));

    coverage.addClass_()
        .setType(new CodeableConcept().addCoding(new Coding(
            "http://terminology.hl7.org/CodeSystem/coverage-class", "group", "Group")))
        .setValue("GRP-001");
    coverage.addClass_()
        .setType(new CodeableConcept().addCoding(new Coding(
            "http://terminology.hl7.org/CodeSystem/coverage-class", "plan", "Plan")))
        .setValue("PLAN-001");

    coverage.addPayor(new Reference("Organization/" + INSURER_ID));

    Period period = new Period();
    period.setStart(new Date());
    coverage.setPeriod(period);

    return coverage;
  }

  static PractitionerRole buildPractitionerRole() {
    PractitionerRole role = new PractitionerRole();
    role.setId(PRACTITIONER_ROLE_ID);
    role.getMeta().addProfile(PasConstants.PROFILE_PAS_PRACTITIONER_ROLE);
    role.setPractitioner(new Reference("Practitioner/" + PRACTITIONER_ID));
    role.addTelecom().setSystem(
        org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem.PHONE).setValue("4029993456");
    return role;
  }

  static Practitioner buildPractitioner() {
    Practitioner practitioner = new Practitioner();
    practitioner.setId(PRACTITIONER_ID);
    practitioner.getMeta().addProfile(PasConstants.PROFILE_PAS_PRACTITIONER);
    practitioner.addIdentifier().setSystem(NPI_SYSTEM).setValue("1234567893");
    practitioner.addName().setFamily("WATSON").addGiven("SUSAN");
    return practitioner;
  }

  private static CodeableConcept buildMemberIdType() {
    // Keep MB for PAS slicing and add MR to satisfy IdentifierType terminology checks.
    return new CodeableConcept()
        .addCoding(new Coding(V2_IDENTIFIER_TYPE_SYSTEM, "MB", "Member Number"))
        .addCoding(new Coding(V2_IDENTIFIER_TYPE_SYSTEM, "MR", "Medical record number"));
  }

  // ===== DTOs =====

  /** A PAS test scenario with $submit and $inquire variants. */
  public record PasScenario(
      String id,
      String name,
      String description,
      String orderType,
      List<PasVariant> variants) {
  }

  /**
   * A single PAS request variant with its FHIR Bundle.
   * 
   * @param operation   the PAS operation: "$submit" or "$inquire"
   * @param payloadType the payload variant: "initial", "renewal", "update",
   *                    "cancel", or "inquiry"
   */
  public record PasVariant(
      String id,
      String label,
      String operation,
      String payloadType,
      Bundle bundle) {
  }

  /** Seed FHIR resources shared across all generated bundles. */
  record SeedResources(
      Patient patient,
      Patient inquiryPatient,
      Organization insurer,
      Organization provider,
      Coverage coverage,
      PractitionerRole practitionerRole,
      Practitioner practitioner) {
  }
}
