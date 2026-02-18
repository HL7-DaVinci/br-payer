package org.hl7.davinci.scenarios;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.hl7.davinci.pas.PasExtensions;
import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
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
 * Produces $submit variants (initial, renewal, update, cancel) and an $inquire variant per scenario.
 * Pure FHIR model logic with no Spring or I/O dependencies.
 */
public class PasRequestBuilder {

  // Profile URLs referenced from PasExtensions for request/inquiry bundles
  static final String PROFILE_PAS_REQUEST_BUNDLE = PasExtensions.PROFILE_PAS_REQUEST_BUNDLE;
  static final String PROFILE_PAS_INQUIRY_REQUEST_BUNDLE = PasExtensions.PROFILE_PAS_INQUIRY_REQUEST_BUNDLE;

  // X12 code systems
  static final String X12_CERT_TYPE_SYSTEM = "https://codesystem.x12.org/005010/1322";
  static final String X12_SERVICE_TYPE_SYSTEM = "https://codesystem.x12.org/005010/1525";

  // NPI system
  static final String NPI_SYSTEM = "http://hl7.org/fhir/sid/us-npi";

  // Shared resource IDs matching examples-pas seed data
  static final String PATIENT_ID = "SubscriberExample";
  static final String INSURER_ID = "InsurerExample";
  static final String PROVIDER_ID = "UMOExample";
  static final String COVERAGE_ID = "InsuranceExample";
  static final String PRACTITIONER_ROLE_ID = "ReferralPractitionerRoleExample";
  static final String PRACTITIONER_ID = "ReferralPractitionerExample";

  private PasRequestBuilder() {}

  /** Build PAS scenarios with $submit variants (initial, renewal, update, cancel) and $inquire. */
  public static List<PasScenario> build(List<ScenarioMetadata> metadataList) {
    SeedResources seed = buildSeedResources();
    List<PasScenario> result = new ArrayList<>();

    for (ScenarioMetadata meta : metadataList) {
      if (meta.focusCodes().isEmpty()) {
        continue;
      }

      Coding focusCode = meta.focusCodes().get(0);
      String description = ScenarioResourceUtil.buildDescription(meta);

      List<PasVariant> variants = new ArrayList<>();
      // $submit variants -- all target the same Claim/$submit endpoint
      variants.add(new PasVariant(
          meta.id() + "-initial", "Initial", "$submit", "initial",
          buildSubmitBundle(meta, focusCode, seed, "I", "Initial")));
      variants.add(new PasVariant(
          meta.id() + "-renewal", "Renewal", "$submit", "renewal",
          buildSubmitBundle(meta, focusCode, seed, "R", "Renewal")));
      variants.add(new PasVariant(
          meta.id() + "-update", "Update", "$submit", "update",
          buildUpdateBundle(meta, focusCode, seed)));
      variants.add(new PasVariant(
          meta.id() + "-cancel", "Cancel", "$submit", "cancel",
          buildCancelBundle(meta, focusCode, seed)));
      // $inquire variant -- targets the Claim/$inquire endpoint
      variants.add(new PasVariant(
          meta.id() + "-inquiry", "Inquiry", "$inquire", "inquiry",
          buildInquiryBundle(meta, focusCode, seed)));

      result.add(new PasScenario(meta.id(), meta.name(), description,
          meta.orderType() != null ? meta.orderType() : "ServiceRequest",
          variants));
    }

    return result;
  }

  // ===== Bundle construction =====

  static Bundle buildSubmitBundle(ScenarioMetadata meta, Coding focusCode, SeedResources seed,
      String certTypeCode, String certTypeDisplay) {
    ServiceRequest serviceRequest = buildServiceRequest(meta, focusCode);
    Claim claim = buildClaim(meta, focusCode, seed, serviceRequest, certTypeCode, certTypeDisplay);
    claim.getMeta().addProfile(PasExtensions.PROFILE_PAS_CLAIM);
    return wrapInBundle(PROFILE_PAS_REQUEST_BUNDLE, claim, seed, false, serviceRequest);
  }

  static Bundle buildInquiryBundle(ScenarioMetadata meta, Coding focusCode, SeedResources seed) {
    ServiceRequest serviceRequest = buildServiceRequest(meta, focusCode);
    Claim claim = buildClaim(meta, focusCode, seed, serviceRequest, "I", "Initial");
    claim.getMeta().addProfile(PasExtensions.PROFILE_PAS_CLAIM_INQUIRY);
    return wrapInBundle(PROFILE_PAS_INQUIRY_REQUEST_BUNDLE, claim, seed, true, serviceRequest);
  }

  static Bundle buildUpdateBundle(ScenarioMetadata meta, Coding focusCode, SeedResources seed) {
    ServiceRequest serviceRequest = buildServiceRequest(meta, focusCode);
    Claim claim = buildClaim(meta, focusCode, seed, serviceRequest, "I", "Initial");
    claim.getMeta().addProfile(PasExtensions.PROFILE_PAS_CLAIM_UPDATE);
    // Synthetic prior auth identifier referencing a hypothetical previous authorization
    claim.addIdentifier(new Identifier()
        .setSystem("http://example.org/PATIENT_EVENT_TRACE_NUMBER")
        .setValue(meta.id() + "-prior-auth-ref"));
    // PAS IG: infoChanged is a regular extension on each Claim.item with a valueCode
    for (Claim.ItemComponent item : claim.getItem()) {
      item.addExtension(new Extension(PasExtensions.INFO_CHANGED, new CodeType("changed")));
    }
    return wrapInBundle(PROFILE_PAS_REQUEST_BUNDLE, claim, seed, false, serviceRequest);
  }

  static Bundle buildCancelBundle(ScenarioMetadata meta, Coding focusCode, SeedResources seed) {
    ServiceRequest serviceRequest = buildServiceRequest(meta, focusCode);
    Claim claim = buildClaim(meta, focusCode, seed, serviceRequest, "I", "Initial");
    claim.getMeta().addProfile(PasExtensions.PROFILE_PAS_CLAIM_UPDATE);
    // Synthetic prior auth identifier referencing a hypothetical previous authorization
    claim.addIdentifier(new Identifier()
        .setSystem("http://example.org/PATIENT_EVENT_TRACE_NUMBER")
        .setValue(meta.id() + "-prior-auth-ref"));
    // PAS IG: infoCancelled is a modifier extension on each Claim.item
    for (Claim.ItemComponent item : claim.getItem()) {
      item.addModifierExtension(
          new Extension(PasExtensions.INFO_CANCELLED, new org.hl7.fhir.r4.model.BooleanType(true)));
    }
    return wrapInBundle(PROFILE_PAS_REQUEST_BUNDLE, claim, seed, false, serviceRequest);
  }

  // ===== Claim construction =====

  static Claim buildClaim(ScenarioMetadata meta, Coding focusCode, SeedResources seed,
      ServiceRequest serviceRequest, String certTypeCode, String certTypeDisplay) {
    Claim claim = new Claim();
    claim.setId(meta.id() + "-claim");

    claim.setStatus(Claim.ClaimStatus.ACTIVE);
    claim.setUse(Claim.Use.PREAUTHORIZATION);
    claim.setCreated(new Date());

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

    // Item with focus code and required PAS extensions
    Claim.ItemComponent item = claim.addItem();
    item.setSequence(1);
    item.setCareTeamSequence(List.of(new org.hl7.fhir.r4.model.PositiveIntType(1)));
    item.setProductOrService(new CodeableConcept().addCoding(focusCode.copy()));

    item.addExtension(PasExtensions.CERTIFICATION_TYPE,
        new CodeableConcept().addCoding(new Coding(X12_CERT_TYPE_SYSTEM, certTypeCode, certTypeDisplay)));

    // serviceItemRequestType = "HS" (Health Services Review)
    item.addExtension(PasExtensions.SERVICE_ITEM_REQUEST_TYPE,
        new CodeableConcept().addCoding(new Coding(X12_SERVICE_TYPE_SYSTEM, "HS",
            "Health Services Review")));

    // itemTraceNumber
    item.addExtension(PasExtensions.ITEM_TRACE_NUMBER,
        new Identifier().setSystem("http://example.org/ITEM_TRACE_NUMBER")
            .setValue(meta.id() + "-trace"));

    // requestedService reference
    item.addExtension("http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-requestedService",
        new Reference("ServiceRequest/" + serviceRequest.getId()));

    return claim;
  }

  static ServiceRequest buildServiceRequest(ScenarioMetadata meta, Coding focusCode) {
    ServiceRequest sr = new ServiceRequest();
    sr.setId(meta.id() + "-service-request");
    sr.getMeta().addProfile(PasExtensions.PROFILE_PAS_SERVICE_REQUEST);
    sr.setStatus(ServiceRequest.ServiceRequestStatus.ACTIVE);
    sr.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
    sr.setCode(new CodeableConcept().addCoding(focusCode.copy()));
    sr.setSubject(new Reference("Patient/" + PATIENT_ID));
    return sr;
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
    Patient patient = buildPatient(false);
    Patient inquiryPatient = buildPatient(true);
    Organization insurer = buildInsurer();
    Organization provider = buildProvider();
    Coverage coverage = buildCoverage();
    PractitionerRole practitionerRole = buildPractitionerRole();
    Practitioner practitioner = buildPractitioner();
    return new SeedResources(patient, inquiryPatient, insurer, provider, coverage,
        practitionerRole, practitioner);
  }

  static Patient buildPatient(boolean includeMbIdentifier) {
    Patient patient = new Patient();
    patient.setId(PATIENT_ID);
    patient.getMeta().addProfile(PasExtensions.PROFILE_PAS_SUBSCRIBER);

    patient.addIdentifier()
        .setSystem("http://example.org/MIN")
        .setValue("12345678901");

    if (includeMbIdentifier) {
      // MB identifier required for $inquire per PAS IG Claim Inquiry profile
      patient.addIdentifier()
          .setType(new CodeableConcept().addCoding(new Coding(
              "http://terminology.hl7.org/CodeSystem/v2-0203", "MB", "Member Number")))
          .setValue("12345678901");
    }

    patient.addName().setFamily("SMITH").addGiven("JOE");
    patient.setGender(Enumerations.AdministrativeGender.MALE);
    return patient;
  }

  static Organization buildInsurer() {
    Organization org = new Organization();
    org.setId(INSURER_ID);
    org.getMeta().addProfile(PasExtensions.PROFILE_PAS_INSURER);
    org.setActive(true);
    org.addIdentifier().setSystem(NPI_SYSTEM).setValue("1234567893");
    org.addType().addCoding(new Coding("https://codesystem.x12.org/005010/98", "PR", null));
    org.setName("MARYLAND CAPITAL INSURANCE COMPANY");
    return org;
  }

  static Organization buildProvider() {
    Organization org = new Organization();
    org.setId(PROVIDER_ID);
    org.getMeta().addProfile(PasExtensions.PROFILE_PAS_REQUESTOR);
    org.setActive(true);
    org.addIdentifier().setSystem(NPI_SYSTEM).setValue("8189991234");
    org.addType().addCoding(new Coding("https://codesystem.x12.org/005010/98", "X3", null));
    org.setName("DR. JOE SMITH CORPORATION");
    return org;
  }

  static Coverage buildCoverage() {
    Coverage coverage = new Coverage();
    coverage.setId(COVERAGE_ID);
    coverage.getMeta().addProfile(PasExtensions.PROFILE_PAS_COVERAGE);
    coverage.setStatus(Coverage.CoverageStatus.ACTIVE);
    coverage.setSubscriberId("1122334455");
    coverage.setBeneficiary(new Reference("Patient/" + PATIENT_ID));
    coverage.getRelationship().addCoding(new Coding(
        "http://terminology.hl7.org/CodeSystem/subscriber-relationship", "self", "Self"));
    coverage.addPayor(new Reference("Organization/" + INSURER_ID));

    Period period = new Period();
    period.setStart(new Date());
    coverage.setPeriod(period);

    return coverage;
  }

  static PractitionerRole buildPractitionerRole() {
    PractitionerRole role = new PractitionerRole();
    role.setId(PRACTITIONER_ROLE_ID);
    role.getMeta().addProfile(PasExtensions.PROFILE_PAS_PRACTITIONER_ROLE);
    role.setPractitioner(new Reference("Practitioner/" + PRACTITIONER_ID));
    role.addTelecom().setSystem(
        org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem.PHONE).setValue("4029993456");
    return role;
  }

  static Practitioner buildPractitioner() {
    Practitioner practitioner = new Practitioner();
    practitioner.setId(PRACTITIONER_ID);
    practitioner.getMeta().addProfile(PasExtensions.PROFILE_PAS_PRACTITIONER);
    practitioner.addIdentifier().setSystem(NPI_SYSTEM).setValue("1234567893");
    practitioner.addName().setFamily("WATSON").addGiven("SUSAN");
    return practitioner;
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
   * @param operation the PAS operation: "$submit" or "$inquire"
   * @param payloadType the payload variant: "initial", "renewal", "update", "cancel", or "inquiry"
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
