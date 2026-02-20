package org.hl7.davinci.scenarios;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.hl7.davinci.pas.PasExtensions;
import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.davinci.scenarios.PasRequestBuilder.PasScenario;
import org.hl7.davinci.scenarios.PasRequestBuilder.PasVariant;
import org.hl7.davinci.scenarios.PasRequestBuilder.SeedResources;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PasRequestBuilderTest {

  private SeedResources seed;

  @BeforeEach
  void setUp() {
    seed = PasRequestBuilder.buildSeedResources();
  }

  @Test
  void build_noFocusCodes_skipsScenario() {
    ScenarioMetadata meta = new ScenarioMetadata(
        "no-codes", "No Codes", null, List.of(), List.of(), null, List.of(), false);

    List<PasScenario> scenarios = PasRequestBuilder.build(List.of(meta));
    assertTrue(scenarios.isEmpty());
  }

  @Test
  void build_withFocusCodes_producesFiveVariants() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");

    List<PasScenario> scenarios = PasRequestBuilder.build(List.of(meta));

    assertEquals(1, scenarios.size());
    PasScenario scenario = scenarios.get(0);
    assertEquals("oxygen", scenario.id());
    assertEquals(5, scenario.variants().size());

    List<String> payloadTypes = scenario.variants().stream().map(PasVariant::payloadType).toList();
    assertTrue(payloadTypes.contains("initial"));
    assertTrue(payloadTypes.contains("renewal"));
    assertTrue(payloadTypes.contains("update"));
    assertTrue(payloadTypes.contains("cancel"));
    assertTrue(payloadTypes.contains("inquiry"));

    // $submit variants
    long submitCount = scenario.variants().stream()
        .filter(v -> "$submit".equals(v.operation())).count();
    assertEquals(4, submitCount);

    // $inquire variant
    long inquireCount = scenario.variants().stream()
        .filter(v -> "$inquire".equals(v.operation())).count();
    assertEquals(1, inquireCount);
  }

  @Test
  void buildSubmitBundle_firstEntryIsClaimWithPreauthorizationUse() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");
    Coding focusCode = meta.focusCodes().get(0);

    Bundle bundle = PasRequestBuilder.buildSubmitBundle(meta, focusCode, seed, "I", "Initial");

    assertEquals(Bundle.BundleType.COLLECTION, bundle.getType());
    assertFalse(bundle.getEntry().isEmpty());

    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();
    assertEquals(Claim.Use.PREAUTHORIZATION, claim.getUse());
  }

  @Test
  void buildSubmitBundle_profileIsRequestBundle() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");

    Bundle bundle = PasRequestBuilder.buildSubmitBundle(meta, meta.focusCodes().get(0), seed, "I", "Initial");

    assertTrue(bundle.getMeta().hasProfile(PasRequestBuilder.PROFILE_PAS_REQUEST_BUNDLE));
  }

  @Test
  void buildSubmitBundle_focusCodeAppearsInClaimItem() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");
    Coding focusCode = meta.focusCodes().get(0);

    Bundle bundle = PasRequestBuilder.buildSubmitBundle(meta, focusCode, seed, "I", "Initial");
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();

    assertFalse(claim.getItem().isEmpty());
    Coding itemCode = claim.getItemFirstRep().getProductOrService().getCodingFirstRep();
    assertEquals("E0424", itemCode.getCode());
    assertEquals("http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", itemCode.getSystem());
  }

  @Test
  void buildSubmitBundle_claimHasRequiredReferences() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");
    Coding focusCode = meta.focusCodes().get(0);

    Bundle bundle = PasRequestBuilder.buildSubmitBundle(meta, focusCode, seed, "I", "Initial");
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();

    assertNotNull(claim.getPatient());
    assertNotNull(claim.getInsurer());
    assertNotNull(claim.getProvider());
    assertFalse(claim.getInsurance().isEmpty());
    assertTrue(claim.getInsurance().get(0).getFocal());
  }

  @Test
  void buildSubmitBundle_itemHasCertificationTypeExtension() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");
    Bundle bundle = PasRequestBuilder.buildSubmitBundle(meta, meta.focusCodes().get(0), seed, "I", "Initial");
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();

    assertNotNull(claim.getItemFirstRep().getExtensionByUrl(PasExtensions.CERTIFICATION_TYPE));
  }

  @Test
  void buildSubmitBundle_renewalVariantHasCertTypeR() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");
    Bundle bundle = PasRequestBuilder.buildSubmitBundle(meta, meta.focusCodes().get(0), seed, "R", "Renewal");
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();

    Extension certTypeExt = claim.getItemFirstRep().getExtensionByUrl(PasExtensions.CERTIFICATION_TYPE);
    assertNotNull(certTypeExt);
    CodeableConcept cc = (CodeableConcept) certTypeExt.getValue();
    assertEquals("R", cc.getCodingFirstRep().getCode());
    assertEquals("Renewal", cc.getCodingFirstRep().getDisplay());
  }

  @Test
  void buildInquiryBundle_profileIsInquiryRequestBundle() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");

    Bundle bundle = PasRequestBuilder.buildInquiryBundle(meta, meta.focusCodes().get(0), seed);

    assertTrue(bundle.getMeta().hasProfile(PasRequestBuilder.PROFILE_PAS_INQUIRY_REQUEST_BUNDLE));
  }

  @Test
  void buildInquiryBundle_patientHasMbIdentifier() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");
    Bundle bundle = PasRequestBuilder.buildInquiryBundle(meta, meta.focusCodes().get(0), seed);

    Patient patient = bundle.getEntry().stream()
        .filter(e -> e.getResource() instanceof Patient)
        .map(e -> (Patient) e.getResource())
        .findFirst()
        .orElseThrow();

    boolean hasMb = patient.getIdentifier().stream()
        .anyMatch(id -> id.hasType() && id.getType().hasCoding()
            && "MB".equals(id.getType().getCodingFirstRep().getCode())
            && id.hasSystem());
    boolean hasMrCompanionCoding = patient.getIdentifier().stream()
        .filter(id -> id.hasType() && id.getType().hasCoding()
            && id.getType().getCoding().stream().anyMatch(c -> "MB".equals(c.getCode())))
        .anyMatch(id -> id.getType().getCoding().stream().anyMatch(c -> "MR".equals(c.getCode())));
    assertTrue(hasMb, "Inquiry patient must have MB-typed identifier");
    assertTrue(hasMrCompanionCoding, "Inquiry MB identifier should include an IdentifierType companion coding");
  }

  @Test
  void buildSubmitBundle_patientSupportsBeneficiaryAndSubscriberProfiles() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");
    Bundle bundle = PasRequestBuilder.buildSubmitBundle(meta, meta.focusCodes().get(0), seed, "I", "Initial");

    Patient patient = bundle.getEntry().stream()
        .filter(e -> e.getResource() instanceof Patient)
        .map(e -> (Patient) e.getResource())
        .findFirst()
        .orElseThrow();

    assertTrue(patient.getMeta().hasProfile(PasExtensions.PROFILE_PAS_BENEFICIARY));
    assertTrue(patient.getMeta().hasProfile(PasExtensions.PROFILE_PAS_SUBSCRIBER));
    assertTrue(patient.getIdentifier().stream().anyMatch(id ->
        id.hasType()
            && id.getType().hasCoding()
            && "MB".equals(id.getType().getCodingFirstRep().getCode())));
  }

  @Test
  void buildInquiryBundle_claimUsesInquiryProfile() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");
    Bundle bundle = PasRequestBuilder.buildInquiryBundle(meta, meta.focusCodes().get(0), seed);
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();

    assertTrue(claim.getMeta().hasProfile(PasExtensions.PROFILE_PAS_CLAIM_INQUIRY));
    assertFalse(claim.getMeta().hasProfile(PasExtensions.PROFILE_PAS_CLAIM));
  }

  @Test
  void buildSeedResources_coverageRelationshipUsesFhirSelfCode() {
    boolean hasFhirSelf = seed.coverage().getRelationship().getCoding().stream()
        .anyMatch(c -> "http://terminology.hl7.org/CodeSystem/subscriber-relationship".equals(c.getSystem())
            && "self".equals(c.getCode()));

    assertTrue(hasFhirSelf, "Coverage.relationship must include FHIR self coding");
    assertNotNull(seed.coverage().getSubscriber(), "Coverage.subscriber should be populated");
    assertEquals("Patient/" + PasRequestBuilder.PATIENT_ID, seed.coverage().getSubscriber().getReference());
  }

  @Test
  void buildSeedResources_coverageHasMemberIdIdentifier() {
    boolean hasMemberIdIdentifier = seed.coverage().getIdentifier().stream()
        .anyMatch(id -> id.hasType()
            && id.getType().hasCoding()
            && id.getType().getCoding().stream().anyMatch(c -> "MB".equals(c.getCode()))
            && id.getType().getCoding().stream().anyMatch(c -> "MR".equals(c.getCode()))
            && PasRequestBuilder.MEMBER_ID_SYSTEM.equals(id.getSystem())
            && seed.coverage().getSubscriberId().equals(id.getValue()));

    assertTrue(hasMemberIdIdentifier, "Coverage must include member ID identifier slice");
  }

  @Test
  void buildSubmitBundle_claimHasIdentifier() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");
    Bundle bundle = PasRequestBuilder.buildSubmitBundle(meta, meta.focusCodes().get(0), seed, "I", "Initial");
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();

    assertFalse(claim.getIdentifier().isEmpty(), "Claim must have an identifier");
    assertEquals("oxygen", claim.getIdentifierFirstRep().getValue());
  }

  @Test
  void buildSubmitBundle_nonPasFocusCodeFallsBackToX12RequestedServiceCode() {
    Coding snomedFocus = new Coding("http://snomed.info/sct", "394579002", "Cardiology");
    ScenarioMetadata meta = buildMetaWithFocus("cardio", "Cardiology", snomedFocus);
    Bundle bundle = PasRequestBuilder.buildSubmitBundle(meta, snomedFocus, seed, "I", "Initial");

    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();
    Coding claimCode = claim.getItemFirstRep().getProductOrService().getCodingFirstRep();
    assertEquals(PasRequestBuilder.X12_REQUESTED_SERVICE_SYSTEM, claimCode.getSystem());
    assertEquals(PasRequestBuilder.DEFAULT_REQUESTED_SERVICE_CODE, claimCode.getCode());

    ServiceRequest serviceRequest = bundle.getEntry().stream()
        .filter(e -> e.getResource() instanceof ServiceRequest)
        .map(e -> (ServiceRequest) e.getResource())
        .findFirst()
        .orElseThrow();
    Coding serviceRequestCode = serviceRequest.getCode().getCodingFirstRep();
    assertEquals(PasRequestBuilder.X12_REQUESTED_SERVICE_SYSTEM, serviceRequestCode.getSystem());
    assertEquals(PasRequestBuilder.DEFAULT_REQUESTED_SERVICE_CODE, serviceRequestCode.getCode());
  }

  @Test
  void buildSubmitBundle_pasAllowedFocusCodeClearsDisplay() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");
    Bundle bundle = PasRequestBuilder.buildSubmitBundle(meta, meta.focusCodes().get(0), seed, "I", "Initial");
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();
    Coding claimCode = claim.getItemFirstRep().getProductOrService().getCodingFirstRep();

    assertFalse(claimCode.hasDisplay(), "Generated PAS request code display should be omitted");
  }

  @Test
  void buildInquiryBundle_claimHasIdentifier() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");
    Bundle bundle = PasRequestBuilder.buildInquiryBundle(meta, meta.focusCodes().get(0), seed);
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();

    assertFalse(claim.getIdentifier().isEmpty(), "Inquiry claim must have identifier (1..1 per profile)");
    assertEquals("oxygen", claim.getIdentifierFirstRep().getValue());
  }

  @Test
  void buildUpdateBundle_itemHasInfoChangedExtension() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");
    Bundle bundle = PasRequestBuilder.buildUpdateBundle(meta, meta.focusCodes().get(0), seed);
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();

    // infoChanged is a regular extension on each Claim.item per PAS IG
    assertNotNull(claim.getItemFirstRep().getExtensionByUrl(PasExtensions.INFO_CHANGED));
    assertTrue(claim.getModifierExtensionsByUrl(PasExtensions.INFO_CHANGED).isEmpty(),
        "infoChanged must not be on the Claim root as a modifier extension");
  }

  @Test
  void buildCancelBundle_itemHasInfoCancelledModifierExtension() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");
    Bundle bundle = PasRequestBuilder.buildCancelBundle(meta, meta.focusCodes().get(0), seed);
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();

    // infoCancelled is a modifier extension on each Claim.item per PAS IG
    boolean itemHasInfoCancelled = claim.getItemFirstRep().getModifierExtension().stream()
        .anyMatch(e -> PasExtensions.INFO_CANCELLED.equals(e.getUrl()));
    assertTrue(itemHasInfoCancelled, "Claim.item must have infoCancelled modifier extension");
    assertTrue(claim.getModifierExtensionsByUrl(PasExtensions.INFO_CANCELLED).isEmpty(),
        "infoCancelled must not be on the Claim root as a modifier extension");
  }

  @Test
  void buildUpdateBundle_claimHasPriorAuthIdentifier() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");
    Bundle bundle = PasRequestBuilder.buildUpdateBundle(meta, meta.focusCodes().get(0), seed);
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();

    boolean hasPriorAuthId = claim.getIdentifier().stream()
        .anyMatch(id -> "http://example.org/PATIENT_EVENT_TRACE_NUMBER".equals(id.getSystem()));
    assertTrue(hasPriorAuthId, "Update bundle Claim must have a synthetic prior auth identifier");
    assertFalse(claim.getRelated().isEmpty(), "Update bundle Claim must reference a prior claim");
    assertEquals("Claim/oxygen-prior-auth-claim", claim.getRelatedFirstRep().getClaim().getReference());
    assertEquals("prior", claim.getRelatedFirstRep().getRelationship().getCodingFirstRep().getCode());
  }

  @Test
  void buildCancelBundle_claimHasPriorAuthIdentifier() {
    ScenarioMetadata meta = buildMeta("oxygen", "Home Oxygen");
    Bundle bundle = PasRequestBuilder.buildCancelBundle(meta, meta.focusCodes().get(0), seed);
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();

    boolean hasPriorAuthId = claim.getIdentifier().stream()
        .anyMatch(id -> "http://example.org/PATIENT_EVENT_TRACE_NUMBER".equals(id.getSystem()));
    assertTrue(hasPriorAuthId, "Cancel bundle Claim must have a synthetic prior auth identifier");
    assertFalse(claim.getRelated().isEmpty(), "Cancel bundle Claim must reference a prior claim");
    assertEquals("Claim/oxygen-prior-auth-claim", claim.getRelatedFirstRep().getClaim().getReference());
    assertEquals("prior", claim.getRelatedFirstRep().getRelationship().getCodingFirstRep().getCode());
  }

  // ===== Helpers =====

  private ScenarioMetadata buildMeta(String id, String name) {
    Coding focusCode = new Coding(
        "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", "E0424", "Stationary Oxygen");
    return buildMetaWithFocus(id, name, focusCode);
  }

  private ScenarioMetadata buildMetaWithFocus(String id, String name, Coding focusCode) {
    return new ScenarioMetadata(
        id, name, null, List.of(focusCode), List.of("order-select"), "DeviceRequest",
        List.of(), false);
  }
}
