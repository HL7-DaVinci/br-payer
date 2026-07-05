package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.*;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PasBundleValidatorTest {

  private PasBundleValidator validator;

  @BeforeEach
  void setUp() {
    validator = new PasBundleValidator();
  }

  @Test
  void validate_nullBundle_throws() {
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(null));
  }

  @Test
  void validate_wrongBundleType_throws() {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.TRANSACTION);
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(bundle));
  }

  @Test
  void validate_emptyBundle_throws() {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(bundle));
  }

  @Test
  void validate_firstEntryNotClaim_throws() {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.addEntry().setResource(new Patient());
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(bundle));
  }

  @Test
  void validate_claimNotPreauthorization_throws() {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    Claim claim = new Claim();
    claim.setUse(Claim.Use.CLAIM);
    bundle.addEntry().setResource(claim);
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(bundle));
  }

  @Test
  void validate_claimMissingPatient_throws() {
    Bundle bundle = buildMinimalBundle();
    ((Claim) bundle.getEntryFirstRep().getResource()).setPatient(null);
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(bundle));
  }

  @Test
  void validate_validBundle_returnsClaim() {
    Bundle bundle = buildMinimalBundle();
    Claim result = validator.validateSubmitBundle(bundle);
    assertNotNull(result);
    assertEquals(Claim.Use.PREAUTHORIZATION, result.getUse());
  }

  @Test
  void validateSubmitBundle_itemMissingCategory_throws() {
    Bundle bundle = buildMinimalBundle();
    ((Claim) bundle.getEntryFirstRep().getResource()).getItemFirstRep().setCategory(null);
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(bundle));
  }

  @Test
  void validateSubmitBundle_itemMissingLocation_throws() {
    Bundle bundle = buildMinimalBundle();
    ((Claim) bundle.getEntryFirstRep().getResource()).getItemFirstRep().setLocation(null);
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(bundle));
  }

  @Test
  void validateInquiryBundle_validBundle_returnsClaim() {
    Bundle bundle = buildMinimalBundleWithMemberIdentifier();
    Claim result = validator.validateInquiryBundle(bundle);
    assertNotNull(result);
  }

  @Test
  void validateInquiryBundle_missingIdentifier_throws() {
    Bundle bundle = buildMinimalBundleWithMemberIdentifier();
    bundle.setIdentifier(null);
    assertThrows(IllegalArgumentException.class, () -> validator.validateInquiryBundle(bundle));
  }

  @Test
  void validateInquiryBundle_missingTimestamp_throws() {
    Bundle bundle = buildMinimalBundleWithMemberIdentifier();
    bundle.setTimestamp(null);
    assertThrows(IllegalArgumentException.class, () -> validator.validateInquiryBundle(bundle));
  }

  @Test
  void validateInquiryBundle_missingEntryFullUrl_throws() {
    Bundle bundle = buildMinimalBundleWithMemberIdentifier();
    bundle.getEntryFirstRep().setFullUrl(null);
    assertThrows(IllegalArgumentException.class, () -> validator.validateInquiryBundle(bundle));
  }

  @Test
  void validateInquiryBundle_missingMemberIdentifier_throws() {
    Bundle bundle = buildMinimalBundleWithMemberIdentifier();
    bundle.getEntry().removeIf(e -> e.getResource() instanceof Patient);
    assertThrows(IllegalArgumentException.class, () -> validator.validateInquiryBundle(bundle));
  }

  @Test
  void validateSubmitBundle_missingIdentifier_throws() {
    Bundle bundle = buildMinimalBundle();
    bundle.setIdentifier(null);
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(bundle));
  }

  @Test
  void validateSubmitBundle_missingTimestamp_throws() {
    Bundle bundle = buildMinimalBundle();
    bundle.setTimestamp(null);
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(bundle));
  }

  @Test
  void validateSubmitBundle_missingEntryFullUrl_throws() {
    Bundle bundle = buildMinimalBundle();
    bundle.getEntryFirstRep().setFullUrl(null);
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(bundle));
  }

  private Bundle buildMinimalBundle() {
    Claim claim = new Claim();
    claim.setUse(Claim.Use.PREAUTHORIZATION);
    claim.setPatient(new Reference("Patient/1"));
    claim.setInsurer(new Reference("Organization/1"));
    claim.setProvider(new Reference("PractitionerRole/1"));
    claim.addInsurance().setCoverage(new Reference("Coverage/1")).setFocal(true);
    claim.addItem().setSequence(1)
        .setProductOrService(new CodeableConcept().addCoding(
            new Coding("http://example.com", "99213", "Office Visit")))
        .setCategory(new CodeableConcept().addCoding(
            new Coding("http://example.com", "outpatient", "Outpatient")))
        .setLocation(new CodeableConcept().addCoding(
            new Coding("http://example.com", "office", "Office")));

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.setIdentifier(
        new Identifier().setSystem("http://example.org/bundles").setValue("bundle-001"));
    bundle.setTimestamp(new java.util.Date());
    bundle.addEntry().setFullUrl("urn:uuid:" + java.util.UUID.randomUUID()).setResource(claim);
    return bundle;
  }

  @Test
  void validateInquiryBundle_missingClaimIdentifier_throws() {
    Bundle bundle = buildMinimalBundleWithMemberIdentifier();
    ((Claim) bundle.getEntryFirstRep().getResource()).getIdentifier().clear();
    assertThrows(IllegalArgumentException.class, () -> validator.validateInquiryBundle(bundle));
  }

  @Test
  void validateInquiryBundle_claimStatusNotActive_throws() {
    Bundle bundle = buildMinimalBundleWithMemberIdentifier();
    ((Claim) bundle.getEntryFirstRep().getResource()).setStatus(Claim.ClaimStatus.CANCELLED);
    assertThrows(IllegalArgumentException.class, () -> validator.validateInquiryBundle(bundle));
  }

  @Test
  void validateInquiryBundle_missingClaimCreated_throws() {
    Bundle bundle = buildMinimalBundleWithMemberIdentifier();
    ((Claim) bundle.getEntryFirstRep().getResource()).setCreated(null);
    assertThrows(IllegalArgumentException.class, () -> validator.validateInquiryBundle(bundle));
  }

  private Bundle buildMinimalBundleWithMemberIdentifier() {
    Bundle bundle = buildMinimalBundle();
    Claim claim = (Claim) bundle.getEntryFirstRep().getResource();
    claim.addIdentifier(
        new Identifier().setSystem("http://example.org/claim-identifiers").setValue("claim-001"));
    claim.setStatus(Claim.ClaimStatus.ACTIVE);
    claim.setCreated(new java.util.Date());
    bundle.setIdentifier(
        new Identifier().setSystem("http://example.org/bundles").setValue("bundle-001"));
    bundle.setTimestamp(new java.util.Date());
    // Add Patient resource with MB identifier to bundle
    Patient patient = new Patient();
    patient.setId("1");
    patient.addIdentifier()
        .setType(new CodeableConcept().addCoding(
            new Coding("http://terminology.hl7.org/CodeSystem/v2-0203", "MB", "Member Number")))
        .setValue("MB123456");
    bundle.addEntry().setFullUrl("Patient/1").setResource(patient);
    return bundle;
  }
}
